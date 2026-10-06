package com.benton.izukijs.runtime

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    fun log(level: LogLevel, message: String) {
        if (level.ordinal < minLevel.ordinal) return
        val entry = LogEntry(counter.incrementAndGet(), System.currentTimeMillis(), level, message)
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > capacity) buffer.removeFirst()
        }
        Log.println(level.toLogcatPriority(), "IzukiJS", message)
        sink?.invoke(entry)
        scheduleEmit()
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
            _entries.value = synchronized(lock) { buffer.toList() }
        }
    }

    fun debug(message: String) = log(LogLevel.DEBUG, message)

    fun info(message: String) = log(LogLevel.INFO, message)

    fun success(message: String) = log(LogLevel.SUCCESS, message)

    fun warn(message: String) = log(LogLevel.WARN, message)

    fun error(message: String) = log(LogLevel.ERROR, message)

    fun clear() {
        synchronized(lock) { buffer.clear() }
        _entries.value = emptyList()
    }

    private fun LogLevel.toLogcatPriority(): Int = when (this) {
        LogLevel.DEBUG -> Log.DEBUG
        LogLevel.INFO -> Log.INFO
        LogLevel.SUCCESS -> Log.INFO
        LogLevel.WARN -> Log.WARN
        LogLevel.ERROR -> Log.ERROR
    }

    private companion object {
        /** 合并窗口，约一帧，兼顾实时性与重组开销。 */
        const val EMIT_INTERVAL_MS = 16L
    }
}
