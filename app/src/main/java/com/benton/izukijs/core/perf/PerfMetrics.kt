package com.benton.izukijs.core.perf

import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 轻量性能基线采集器。
 *
 * 关闭时 [measure] / [record] 只做一次 volatile 判断，几乎无开销；开启后记录：
 * - `ui.frame`：主窗口每帧总耗时（[FrameMetrics.TOTAL_DURATION]）；
 * - 各模块通过 [measure] 上报的耗时（日志刷新、截图、找图找色、OCR、预览绘制等）；
 * - [count] 计数器与 [setGauge] 瞬时值。
 *
 * 每 [REPORT_INTERVAL_MS] 以窗口为单位输出一条汇总到 logcat（tag `IzukiPerf`），
 * 输出后清空窗口，方便一次只观察一个场景。也可在设置页手动触发 [reportNow]。
 */
object PerfMetrics {

    private const val TAG = "IzukiPerf"
    private const val REPORT_INTERVAL_MS = 30_000L
    /** 30 秒窗口内 120Hz 设备约 3600 帧，4096 个样本可覆盖完整窗口。 */
    private const val SAMPLE_CAPACITY = 4_096
    private const val FRAME_MARGIN_NANOS = 1_000_000L

    @Volatile
    var enabled: Boolean = false
        private set

    private val metrics = ConcurrentHashMap<String, Metric>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val gauges = ConcurrentHashMap<String, AtomicLong>()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val reportThread: HandlerThread by lazy {
        HandlerThread("izuki-perf-report").apply { start() }
    }
    private val reportHandler: Handler by lazy { Handler(reportThread.looper) }

    private val reportRunnable = object : Runnable {
        override fun run() {
            if (!enabled) return
            reportNow()
            reportHandler.postDelayed(this, REPORT_INTERVAL_MS)
        }
    }

    @Volatile
    private var windowRef: WeakReference<Window>? = null

    @Volatile
    private var slowFrameNanos = 16_666_667L

    @Volatile
    private var sessionStartedAt = 0L

    private var frameThread: HandlerThread? = null

    private var frameListener: Window.OnFrameMetricsAvailableListener? = null

    private val frameMetricsListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        record("ui.frame", duration)
        if (duration > slowFrameNanos) count("ui.frame.slow")
    }

    /** 绑定主窗口；之后 [setEnabled] 会按需挂载 / 卸载帧监听。 */
    fun bindWindow(window: Window) {
        mainHandler.post {
            windowRef = WeakReference(window)
            slowFrameNanos = resolveSlowFrameNanos(window)
            if (enabled) attachFrameListenerLocked()
        }
    }

    fun unbindWindow(window: Window) {
        mainHandler.post {
            if (windowRef?.get() === window) {
                detachFrameListenerLocked()
                windowRef = null
            }
        }
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (value) reset()
        mainHandler.post {
            if (value) {
                sessionStartedAt = SystemClock.elapsedRealtime()
                attachFrameListenerLocked()
            } else {
                detachFrameListenerLocked()
            }
        }
        reportHandler.removeCallbacks(reportRunnable)
        if (value) {
            Log.i(TAG, "perf baseline enabled")
            reportHandler.postDelayed(reportRunnable, REPORT_INTERVAL_MS)
        } else {
            Log.i(TAG, "perf baseline disabled")
        }
    }

    /** 记录一段耗时。禁用时直接执行 block，不做任何计时。 */
    inline fun <T> measure(name: String, block: () -> T): T {
        if (!enabled) return block()
        val start = SystemClock.elapsedRealtimeNanos()
        try {
            return block()
        } finally {
            record(name, SystemClock.elapsedRealtimeNanos() - start)
        }
    }

    /** 记录一个耗时样本（纳秒）。 */
    fun record(name: String, nanos: Long) {
        if (!enabled) return
        metrics.computeIfAbsent(name) { Metric() }.add(nanos)
    }

    /** 累加计数器，用于统计调用次数 / 命中次数。 */
    fun count(name: String, delta: Long = 1L) {
        if (!enabled) return
        counters.computeIfAbsent(name) { AtomicLong() }.addAndGet(delta)
    }

    /** 设置瞬时值（如日志缓冲区大小），每次上报取最后一次写入的值。 */
    fun setGauge(name: String, value: Long) {
        if (!enabled) return
        gauges.computeIfAbsent(name) { AtomicLong() }.set(value)
    }

    /**
     * 输出当前窗口的汇总并清空计时 / 计数（瞬时值保留）。
     *
     * 每行一个指标，避免 logcat 单行截断。用 `adb logcat -s IzukiPerf` 查看。
     */
    fun reportNow() {
        if (!enabled) return
        val elapsed = if (sessionStartedAt > 0L) {
            SystemClock.elapsedRealtime() - sessionStartedAt
        } else {
            0L
        }
        val frameMetric = metrics["ui.frame"]
        val frameCount = frameMetric?.count() ?: 0L
        val slowFrames = counters["ui.frame.slow"]?.get() ?: 0L
        val jankPercent = if (frameCount > 0L) slowFrames * 100.0 / frameCount else 0.0
        val runtime = Runtime.getRuntime()
        val javaHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)
        val nativeHeapMb = runCatching { Debug.getNativeHeapAllocatedSize() }
            .getOrDefault(0L) / (1024.0 * 1024.0)
        val slowFrameMs = slowFrameNanos / 1_000_000.0

        Log.i(
            TAG,
            String.format(
                Locale.US,
                "=== report session=%ds device=%s sdk=%d slowFrame=%.1fms frames=%d jank=%d(%.1f%%) java=%.1fMB native=%.1fMB",
                elapsed / 1000L,
                Build.MODEL.orEmpty(),
                Build.VERSION.SDK_INT,
                slowFrameMs,
                frameCount,
                slowFrames,
                jankPercent,
                javaHeapMb,
                nativeHeapMb,
            ),
        )

        metrics.entries
            .sortedBy { it.key }
            .forEach { (name, metric) ->
                logMetric(name, metric)
            }

        counters.entries
            .sortedBy { it.key }
            .forEach { (name, value) ->
                if (value.get() > 0L) Log.i(TAG, "count $name=${value.get()}")
            }

        gauges.entries
            .sortedBy { it.key }
            .forEach { (name, value) ->
                Log.i(TAG, "gauge $name=${value.get()}")
            }

        metrics.clear()
        counters.clear()
    }

    fun reset() {
        metrics.clear()
        counters.clear()
        gauges.clear()
        sessionStartedAt = SystemClock.elapsedRealtime()
    }

    private fun logMetric(name: String, metric: Metric) {
        val samples = metric.snapshot()
        if (samples.isEmpty()) return
        val sorted = samples.sortedArray()
        val count = metric.count()
        val avgUs = metric.totalNanos() / count / 1_000.0
        Log.i(
            TAG,
            String.format(
                Locale.US,
                "time %s n=%d avg=%.1fus p50=%.1fus p95=%.1fus p99=%.1fus max=%.1fus",
                name,
                count,
                avgUs,
                percentile(sorted, 0.50) / 1_000.0,
                percentile(sorted, 0.95) / 1_000.0,
                percentile(sorted, 0.99) / 1_000.0,
                metric.maxNanos() / 1_000.0,
            ),
        )
    }

    private fun percentile(sorted: LongArray, fraction: Double): Long {
        if (sorted.isEmpty()) return 0L
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun attachFrameListenerLocked() {
        val window = windowRef?.get() ?: return
        if (frameListener != null) return
        val thread = HandlerThread("izuki-perf-frames").apply { start() }
        frameThread = thread
        frameListener = frameMetricsListener
        runCatching {
            window.addOnFrameMetricsAvailableListener(frameMetricsListener, Handler(thread.looper))
        }.onFailure {
            Log.w(TAG, "attach frame metrics failed: ${it.message}")
            frameListener = null
            thread.quitSafely()
            frameThread = null
        }
    }

    private fun detachFrameListenerLocked() {
        val window = windowRef?.get()
        val listener = frameListener
        if (window != null && listener != null) {
            runCatching { window.removeOnFrameMetricsAvailableListener(listener) }
        }
        frameListener = null
        frameThread?.quitSafely()
        frameThread = null
    }

    private fun resolveSlowFrameNanos(window: Window): Long {
        @Suppress("DEPRECATION")
        val refreshRate = runCatching { window.windowManager.defaultDisplay.refreshRate }
            .getOrDefault(60f)
            .takeIf { it > 1f } ?: 60f
        return (1_000_000_000.0 / refreshRate).toLong() + FRAME_MARGIN_NANOS
    }

    private class Metric {
        private val count = AtomicLong(0)
        private val totalNanos = AtomicLong(0)
        private val maxNanos = AtomicLong(0)
        private val samples = LongArray(SAMPLE_CAPACITY)
        private var sampleCount = 0
        private var nextSample = 0

        fun add(value: Long) {
            count.incrementAndGet()
            totalNanos.addAndGet(value)
            maxNanos.updateAndGet { current -> if (value > current) value else current }
            synchronized(samples) {
                samples[nextSample] = value
                nextSample = (nextSample + 1) % SAMPLE_CAPACITY
                if (sampleCount < SAMPLE_CAPACITY) sampleCount++
            }
        }

        fun count(): Long = count.get()

        fun totalNanos(): Long = totalNanos.get()

        fun maxNanos(): Long = maxNanos.get()

        fun snapshot(): LongArray = synchronized(samples) {
            LongArray(sampleCount) { samples[it] }
        }
    }
}
