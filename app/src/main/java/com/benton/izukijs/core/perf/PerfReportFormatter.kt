package com.benton.izukijs.core.perf

import java.util.Locale

/**
 * 性能报告格式化。纯函数，不读取任何系统状态，便于单元测试。
 */
internal object PerfReportFormatter {

    fun summary(
        sessionSeconds: Long,
        device: String,
        sdk: Int,
        slowFrameMs: Double,
        frames: Long,
        slowFrames: Long,
        jankPercent: Double,
        javaHeapMb: Double,
        nativeHeapMb: Double,
    ): String = String.format(
        Locale.US,
        "=== report session=%ds device=%s sdk=%d slowFrame=%.1fms frames=%d jank=%d(%.1f%%) java=%.1fMB native=%.1fMB",
        sessionSeconds,
        device,
        sdk,
        slowFrameMs,
        frames,
        slowFrames,
        jankPercent,
        javaHeapMb,
        nativeHeapMb,
    )

    fun metric(
        name: String,
        count: Long,
        avgUs: Double,
        p50Us: Double,
        p95Us: Double,
        p99Us: Double,
        maxUs: Double,
    ): String = String.format(
        Locale.US,
        "time %s n=%d avg=%.1fus p50=%.1fus p95=%.1fus p99=%.1fus max=%.1fus",
        name,
        count,
        avgUs,
        p50Us,
        p95Us,
        p99Us,
        maxUs,
    )

    fun counter(name: String, value: Long): String = "count $name=$value"

    fun gauge(name: String, value: Long): String = "gauge $name=$value"

    /** 日志页中的汇总条目，统一加 `[perf]` 前缀便于筛选。 */
    fun summaryEntry(summary: String): String = "[perf] $summary"

    /**
     * 日志页中的多行明细条目。
     *
     * 换行只用于内存展示；落盘时 [com.benton.izukijs.runtime.LogFileStore] 会转义为 `\n`，
     * 保持日志文件仍是一行一条、可按 `time|LEVEL|message` 解析。
     */
    fun detailEntry(lines: List<String>): String =
        if (lines.isEmpty()) "" else "[perf] 明细\n" + lines.joinToString("\n")
}
