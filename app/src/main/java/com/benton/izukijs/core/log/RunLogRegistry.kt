package com.benton.izukijs.core.log

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement
import java.util.concurrent.atomic.AtomicLong

private const val MAX_RUNS = 20
private const val MAX_ENTRIES = 2_000
private const val INITIAL_CAPACITY = 64
private const val MAX_TAIL = 2_000

/** 一次脚本运行的状态。 */
enum class RunStatus { RUNNING, SUCCEEDED, FAILED, TIMEOUT, STOPPED }

/** 一次脚本运行的内存记录（含该次运行的日志尾部）。 */
class RunRecord(
    val runId: String,
    val scriptId: String,
    val scriptName: String,
    val startedAt: Long,
) {
    @Volatile
    var finishedAt: Long = 0L

    @Volatile
    var status: RunStatus = RunStatus.RUNNING

    @Volatile
    var error: String? = null

    private val lock = Any()
    private val entries = ArrayDeque<LogEntry>(INITIAL_CAPACITY)

    fun append(entry: LogEntry) {
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    fun snapshot(): List<LogEntry> = synchronized(lock) { entries.toList() }
}

/**
 * 脚本运行日志登记表。
 *
 * 用 [ThreadLocal] 标记当前脚本执行线程，[LogBus] 在写入全局日志时顺带记录一份到对应 run，
 * 让 MCP 能按 run_id 取回与本次运行配对的输出。
 */
object RunLogRegistry {

    private val runs = LinkedHashMap<String, RunRecord>()
    private val current = ThreadLocal<String?>()
    private val sequence = AtomicLong(0)

    fun newRunId(): String =
        "run-${System.currentTimeMillis().toString(36)}-${sequence.incrementAndGet().toString(36)}"

    fun begin(runId: String, scriptId: String, scriptName: String): RunRecord {
        val record = RunRecord(runId, scriptId, scriptName, System.currentTimeMillis())
        synchronized(runs) {
            runs[runId] = record
            while (runs.size > MAX_RUNS) {
                val oldest = runs.keys.firstOrNull() ?: break
                runs.remove(oldest)
            }
        }
        return record
    }

    /**
     * 把 [runId] 绑定到协程上下文。
     *
     * 直接使用 [ThreadLocal] 时，协程在挂起点被调度到另一线程后 [record] 会丢失 runId，
     * [exit] 也可能清不掉原线程的标记。用 [asContextElement] 包裹后，线程切换时自动
     * 恢复该值，脚本运行期间的日志即可稳定归档到对应 run。
     *
     * 注意：脚本内部另起线程 / 独立 scope 不会继承此上下文。
     */
    fun asContextElement(runId: String): ThreadContextElement<String?> =
        current.asContextElement(runId)

    fun enter(runId: String) {
        current.set(runId)
    }

    fun exit() {
        current.remove()
    }

    fun record(entry: LogEntry) {
        val runId = current.get() ?: return
        val record = synchronized(runs) { runs[runId] } ?: return
        record.append(entry)
    }

    fun finish(runId: String, status: RunStatus, error: String? = null) {
        val record = synchronized(runs) { runs[runId] } ?: return
        record.status = status
        record.error = error
        record.finishedAt = System.currentTimeMillis()
    }

    fun find(runId: String): RunRecord? = synchronized(runs) { runs[runId] }

    fun recent(limit: Int = 10): List<RunRecord> =
        synchronized(runs) { runs.values.toList().takeLast(limit.coerceAtLeast(1)) }

    fun filter(
        record: RunRecord,
        tail: Int,
        level: LogLevel?,
        grep: String?,
    ): List<LogEntry> {
        var entries = record.snapshot()
        if (level != null) {
            entries = entries.filter { it.level.ordinal >= level.ordinal }
        }
        if (!grep.isNullOrBlank()) {
            entries = entries.filter { it.message.contains(grep, ignoreCase = true) }
        }
        return entries.takeLast(tail.coerceIn(1, MAX_TAIL))
    }
}
