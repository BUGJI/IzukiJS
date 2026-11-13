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
class RunLogRecord(
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

    private val runs = LinkedHashMap<String, RunLogRecord>()
    private val current = ThreadLocal<String?>()
    private val sequence = AtomicLong(0)

    /**
     * 最近一次开始的 run。
     *
     * QuickJS 的 JS → Java bridge 回调不一定运行在挂着 [current] 的协程线程上，
     * 此时 `console.log` 取不到 runId。脚本运行由全局锁串行化，因此用最近开始的 run
     * 兜底不会串到别的运行；仅当该 run 仍在 RUNNING 时生效，避免污染历史记录。
     */
    @Volatile
    private var lastActiveRunId: String? = null

    fun newRunId(): String =
        "run-${System.currentTimeMillis().toString(36)}-${sequence.incrementAndGet().toString(36)}"

    fun begin(runId: String, scriptId: String, scriptName: String): RunLogRecord {
        val record = RunLogRecord(runId, scriptId, scriptName, System.currentTimeMillis())
        synchronized(runs) {
            runs[runId] = record
            while (runs.size > MAX_RUNS) {
                val oldest = runs.keys.firstOrNull() ?: break
                runs.remove(oldest)
            }
        }
        lastActiveRunId = runId
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
        val runId = current.get() ?: lastActiveRunId ?: return
        val record = synchronized(runs) { runs[runId] } ?: return
        // 兜底只用于仍在运行的脚本；显式 runId（ConsoleApi）允许补记收尾输出。
        if (current.get() == null && record.status != RunStatus.RUNNING) return
        record.append(entry)
    }

    /** 显式归档：用于 JS bridge 等不继承协程 ThreadLocal 的线程。 */
    fun record(entry: LogEntry, runId: String?) {
        if (runId == null) {
            record(entry)
            return
        }
        val record = synchronized(runs) { runs[runId] } ?: return
        record.append(entry)
    }

    fun finish(runId: String, status: RunStatus, error: String? = null) {
        val record = synchronized(runs) { runs[runId] } ?: return
        record.status = status
        record.error = error
        record.finishedAt = System.currentTimeMillis()
        if (lastActiveRunId == runId) lastActiveRunId = null
    }

    fun find(runId: String): RunLogRecord? = synchronized(runs) { runs[runId] }

    fun recent(limit: Int = 10): List<RunLogRecord> =
        synchronized(runs) { runs.values.toList().takeLast(limit.coerceAtLeast(1)) }

    fun filter(
        record: RunLogRecord,
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
