package com.benton.izukijs.core.log

import android.os.SystemClock
import android.util.Log
import com.benton.izukijs.core.perf.PerfMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel { DEBUG, INFO, SUCCESS, WARN, ERROR }

data class LogEntry(
    val id: Long,
    val timeMillis: Long,
    val level: LogLevel,
    val message: String,
)

/**
 * 全局日志总线。脚本的控制台输出、引擎日志都汇聚到这里，UI 订阅 [entries]。
 */
class LogBus(private val capacity: Int = 500) {

    private val counter = AtomicLong(0)
    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(capacity)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val emitScheduled = AtomicBoolean(false)

    /** 高频 DEBUG 日志的采样时间戳；key 必须是稳定的类别名，避免无限增长。 */
    private val throttleTimestamps = ConcurrentHashMap<String, Long>()

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    /** 持久化接收器（写入文件）。由容器注入，避免运行时直接依赖存储层。 */
    @Volatile
    private var sink: ((LogEntry) -> Unit)? = null

    /** 低于该级别的日志被直接丢弃（同时不落盘）。由设置实时同步。 */
    @Volatile
    var minLevel: LogLevel = LogLevel.DEBUG

    fun setSink(value: ((LogEntry) -> Unit)?) {
        sink = value
    }

    fun log(level: LogLevel, message: String, runId: String? = null) {
        if (level.ordinal < minLevel.ordinal) {
            PerfMetrics.count("log.dropped")
            return
        }
        PerfMetrics.count("log.append")
        PerfMetrics.measure("log.append") {
            val entry = LogEntry(counter.incrementAndGet(), System.currentTimeMillis(), level, message)
            synchronized(lock) {
                buffer.addLast(entry)
                while (buffer.size > capacity) buffer.removeFirst()
            }
            Log.println(level.toLogcatPriority(), "IzukiJS", message)
            RunLogRegistry.record(entry, runId)
            sink?.invoke(entry)
            scheduleEmit()
        }
    }

    /**
     * 合并高频日志：[EMIT_INTERVAL_MS] 内的多次写入只触发一次订阅刷新，
     * 避免脚本密集输出时每个条目都重建列表并重组 UI。
     */
    private fun scheduleEmit() {
        if (!emitScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(EMIT_INTERVAL_MS)
            emitScheduled.set(false)
            PerfMetrics.measure("log.emit") {
                // 快照与发布必须在同一把锁内，否则 clear() 可能插在中间把旧日志写回来。
                synchronized(lock) {
                    val snapshot = buffer.toList()
                    _entries.value = snapshot
                    PerfMetrics.setGauge("log.buffer", snapshot.size.toLong())
                }
            }
        }
    }

    fun debug(message: String, runId: String? = null) = log(LogLevel.DEBUG, message, runId)

    /**
     * 按稳定 [key] 采样 DEBUG 日志：同一类操作在 [intervalMs] 内只记录第一条。
     *
     * 用于找图 / 找色等可能每秒几十次的内部诊断日志，避免刷屏并降低 logcat / 落盘开销。
     * 脚本自己的 `console.log` 不受影响。
     */
    fun debugThrottled(
        key: String,
        message: String,
        intervalMs: Long = 500L,
    ) {
        if (minLevel.ordinal > LogLevel.DEBUG.ordinal) {
            PerfMetrics.count("log.dropped")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val last = throttleTimestamps[key]
        if (last != null && now - last < intervalMs) {
            PerfMetrics.count("log.throttled")
            return
        }
        throttleTimestamps[key] = now
        debug(message)
    }

    fun info(message: String, runId: String? = null) = log(LogLevel.INFO, message, runId)

    fun success(message: String, runId: String? = null) = log(LogLevel.SUCCESS, message, runId)

    fun warn(message: String, runId: String? = null) = log(LogLevel.WARN, message, runId)

    fun error(message: String, runId: String? = null) = log(LogLevel.ERROR, message, runId)

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
        throttleTimestamps.clear()
    }

    private fun LogLevel.toLogcatPriority(): Int = when (this) {
        LogLevel.DEBUG -> Log.DEBUG
        LogLevel.INFO -> Log.INFO
        LogLevel.SUCCESS -> Log.INFO
        LogLevel.WARN -> Log.WARN
        LogLevel.ERROR -> Log.ERROR
    }

    private companion object {
        /**
         * 合并窗口。50ms 对日志观感几乎无差别，但把高频脚本输出时的列表重建 / 重组次数
         * 从最多 60 次/秒压到 20 次/秒，明显降低主线程压力。
         */
        const val EMIT_INTERVAL_MS = 50L
    }
}
