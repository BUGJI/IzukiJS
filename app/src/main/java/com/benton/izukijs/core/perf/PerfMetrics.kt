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
import java.io.File
import java.lang.ref.WeakReference
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
 * 每 [REPORT_INTERVAL_MS] 以窗口为单位输出一次汇总：logcat（tag `IzukiPerf`）逐行输出，
 * 同时通过 [setReportSink] 写入应用内日志（1 条汇总 + 1 条多行明细），输出后清空窗口，
 * 方便一次只观察一个场景。也可在设置页手动触发 [reportNow]。
 */
object PerfMetrics {

    private const val TAG = "IzukiPerf"
    private const val REPORT_INTERVAL_MS = 30_000L
    /** 30 秒窗口内 120Hz 设备约 3600 帧，4096 个样本可覆盖完整窗口。 */
    private const val SAMPLE_CAPACITY = 4_096
    private const val FRAME_MARGIN_NANOS = 1_000_000L
    private const val MB = 1024L * 1024L

    @Volatile
    var enabled: Boolean = false
        private set

    private val metrics = ConcurrentHashMap<String, MetricAccumulator>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val gauges = ConcurrentHashMap<String, AtomicLong>()

    /**
     * 报告输出回调（由容器注入 `LogBus`）。不直接依赖日志模块，避免与 LogBus 形成循环依赖。
     *
     * [emitting] 为真时暂停采样，防止「写性能报告」本身被计入 log.append / log.emit。
     */
    @Volatile
    private var reportSink: ((String) -> Unit)? = null

    @Volatile
    @PublishedApi
    internal var emitting = false

    @Volatile
    private var lastGcCount = 0L

    @Volatile
    private var lastGcTimeMs = 0L

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
        record("ui.layout", metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION))
        record("ui.draw", metrics.getMetric(FrameMetrics.DRAW_DURATION))
        record("ui.command", metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION))
        record("ui.swap", metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            record("ui.sync", metrics.getMetric(FrameMetrics.SYNC_DURATION))
            record("ui.gpu", metrics.getMetric(FrameMetrics.GPU_DURATION))
        }
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

    /** 注入性能报告输出（通常为应用内日志）。传 null 取消。 */
    fun setReportSink(sink: ((String) -> Unit)?) {
        reportSink = sink
    }

    /** 记录一段耗时。禁用时直接执行 block，不做任何计时。 */
    inline fun <T> measure(name: String, block: () -> T): T {
        if (!enabled || emitting) return block()
        val start = SystemClock.elapsedRealtimeNanos()
        try {
            return block()
        } finally {
            record(name, SystemClock.elapsedRealtimeNanos() - start)
        }
    }

    /** 记录一个耗时样本（纳秒）。 */
    fun record(name: String, nanos: Long) {
        if (!enabled || emitting) return
        metrics.computeIfAbsent(name) { MetricAccumulator(SAMPLE_CAPACITY) }.add(nanos)
    }

    /** 累加计数器，用于统计调用次数 / 命中次数。 */
    fun count(name: String, delta: Long = 1L) {
        if (!enabled || emitting) return
        counters.computeIfAbsent(name) { AtomicLong() }.addAndGet(delta)
    }

    /** 设置瞬时值（如日志缓冲区大小），每次上报取最后一次写入的值。 */
    fun setGauge(name: String, value: Long) {
        if (!enabled || emitting) return
        gauges.computeIfAbsent(name) { AtomicLong() }.set(value)
    }

    /**
     * 输出当前窗口的汇总并清空计时 / 计数（瞬时值保留）。
     *
     * logcat 仍逐行输出（`adb logcat -s IzukiPerf`）；若注入了 [setReportSink]，
     * 同时向应用内日志写「1 条汇总 + 1 条多行明细」。从非报告线程调用时会投递到报告线程，
     * 避免在主线程做格式化与写日志。
     */
    fun reportNow() {
        if (!enabled) return
        val looper = reportThread.looper
        if (Looper.myLooper() == looper) {
            reportNowInternal()
        } else {
            reportHandler.post { reportNowInternal() }
        }
    }

    private fun reportNowInternal() {
        if (!enabled) return
        val report = buildReport()
        metrics.clear()
        counters.clear()

        Log.i(TAG, report.summary)
        report.detailLines.forEach { Log.i(TAG, it) }

        val sink = reportSink ?: return
        emitting = true
        try {
            runCatching {
                sink(PerfReportFormatter.summaryEntry(report.summary))
                val detail = PerfReportFormatter.detailEntry(report.detailLines)
                if (detail.isNotEmpty()) sink(detail)
            }
        } finally {
            emitting = false
        }
    }

    fun reset() {
        metrics.clear()
        counters.clear()
        gauges.clear()
        sessionStartedAt = SystemClock.elapsedRealtime()
        lastGcCount = readGcStat("art.gc.gc-count")
        lastGcTimeMs = readGcStat("art.gc.gc-time")
    }

    private fun buildReport(): Report {
        collectSystemGauges()

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

        val summary = PerfReportFormatter.summary(
            sessionSeconds = elapsed / 1000L,
            device = Build.MODEL.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
            slowFrameMs = slowFrameMs,
            frames = frameCount,
            slowFrames = slowFrames,
            jankPercent = jankPercent,
            javaHeapMb = javaHeapMb,
            nativeHeapMb = nativeHeapMb,
        )

        val detailLines = buildList {
            metrics.entries
                .sortedBy { it.key }
                .forEach { (name, metric) ->
                    metricLine(name, metric)?.let(::add)
                }

            counters.entries
                .sortedBy { it.key }
                .forEach { (name, value) ->
                    if (value.get() > 0L) {
                        add(PerfReportFormatter.counter(name, value.get()))
                    }
                }

            gauges.entries
                .sortedBy { it.key }
                .forEach { (name, value) ->
                    add(PerfReportFormatter.gauge(name, value.get()))
                }
        }

        return Report(summary, detailLines)
    }

    /** 采集内存 / GC / 线程 / FD 等低频瞬时值，作为 gauge 随报告输出。 */
    private fun collectSystemGauges() {
        val runtime = Runtime.getRuntime()
        setGauge("mem.java.used", (runtime.totalMemory() - runtime.freeMemory()) / MB)
        setGauge("mem.java.max", runtime.maxMemory() / MB)
        setGauge("mem.native", runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L) / MB)

        val memoryInfo = Debug.MemoryInfo()
        runCatching { Debug.getMemoryInfo(memoryInfo) }
        setGauge("mem.pss.kb", memoryInfo.totalPss.toLong())

        setGauge("threads", Thread.activeCount().toLong())
        setGauge(
            "fd.count",
            runCatching { File("/proc/self/fd").list()?.size?.toLong() ?: 0L }.getOrDefault(0L),
        )
        setGauge("uptime", SystemClock.elapsedRealtime() / 1000L)

        val gcCount = readGcStat("art.gc.gc-count")
        val gcTimeMs = readGcStat("art.gc.gc-time")
        setGauge("gc.count", (gcCount - lastGcCount).coerceAtLeast(0L))
        setGauge("gc.time.ms", (gcTimeMs - lastGcTimeMs).coerceAtLeast(0L))
        lastGcCount = gcCount
        lastGcTimeMs = gcTimeMs
    }

    private fun readGcStat(name: String): Long =
        runCatching { Debug.getRuntimeStat(name)?.toLongOrNull() ?: 0L }.getOrDefault(0L)

    private fun metricLine(name: String, metric: MetricAccumulator): String? {
        val samples = metric.snapshot()
        if (samples.isEmpty()) return null
        val sorted = samples.sortedArray()
        val count = metric.count()
        val avgUs = metric.totalNanos() / count / 1_000.0
        return PerfReportFormatter.metric(
            name = name,
            count = count,
            avgUs = avgUs,
            p50Us = percentile(sorted, 0.50) / 1_000.0,
            p95Us = percentile(sorted, 0.95) / 1_000.0,
            p99Us = percentile(sorted, 0.99) / 1_000.0,
            maxUs = metric.maxNanos() / 1_000.0,
        )
    }

    private fun percentile(sorted: LongArray, fraction: Double): Long =
        PerfStats.percentile(sorted, fraction)

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

    private data class Report(val summary: String, val detailLines: List<String>)

}
