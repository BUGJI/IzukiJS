package com.benton.izukijs.core.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfReportFormatterTest {

    @Test
    fun summaryUsesStableUsFormatting() {
        val summary = PerfReportFormatter.summary(
            sessionSeconds = 30,
            device = "Pixel",
            sdk = 34,
            slowFrameMs = 17.7,
            frames = 1800,
            slowFrames = 42,
            jankPercent = 2.33,
            javaHeapMb = 12.5,
            nativeHeapMb = 3.25,
        )

        assertTrue(summary.startsWith("=== report session=30s device=Pixel sdk=34"))
        assertTrue(summary.contains("slowFrame=17.7ms frames=1800 jank=42(2.3%)"))
        assertTrue(summary.contains("java=12.5MB native=3.3MB"))
    }

    @Test
    fun metricFormatsPercentiles() {
        val line = PerfReportFormatter.metric(
            name = "ui.frame",
            count = 1800,
            avgUs = 6100.0,
            p50Us = 5800.0,
            p95Us = 11200.0,
            p99Us = 24500.0,
            maxUs = 38100.0,
        )

        assertEquals(
            "time ui.frame n=1800 avg=6100.0us p50=5800.0us p95=11200.0us p99=24500.0us max=38100.0us",
            line,
        )
    }

    @Test
    fun detailEntryIsSingleMultilineLogEntry() {
        val entry = PerfReportFormatter.detailEntry(
            listOf("time ui.frame n=1 avg=1.0us", PerfReportFormatter.counter("log.append", 12)),
        )

        assertTrue(entry.startsWith("[perf] 明细\n"))
        assertEquals("[perf] 明细\ntime ui.frame n=1 avg=1.0us\ncount log.append=12", entry)
    }

    @Test
    fun emptyDetailEntryIsSkipped() {
        assertEquals("", PerfReportFormatter.detailEntry(emptyList()))
    }

    @Test
    fun summaryEntryHasPerfPrefix() {
        assertEquals("[perf] === report", PerfReportFormatter.summaryEntry("=== report"))
    }
}
