package com.benton.izukijs.schedule

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CronExpressionTest {

    private val zone = ZoneId.of("UTC")

    private fun millis(text: String): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun next(expr: String, from: String): String {
        val result = CronExpression.parse(expr)!!.nextAfter(millis(from), zone)!!
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(result), zone).toString()
    }

    @Test
    fun parsesEveryFifteenMinutes() {
        assertEquals("2026-01-01T00:15", next("*/15 * * * *", "2026-01-01T00:00"))
        assertEquals("2026-01-01T01:00", next("*/15 * * * *", "2026-01-01T00:46"))
    }

    @Test
    fun parsesDailyTime() {
        assertEquals("2026-01-02T08:30", next("30 8 * * *", "2026-01-01T09:00"))
        assertEquals("2026-01-01T08:30", next("30 8 * * *", "2026-01-01T08:00"))
    }

    @Test
    fun parsesListsAndRanges() {
        assertEquals("2026-01-01T09:30", next("0,30 9-17 * * *", "2026-01-01T09:01"))
        assertEquals("2026-01-02T09:30", next("30 9-17 * * *", "2026-01-01T18:00"))
    }

    @Test
    fun aliasesWork() {
        assertEquals("2026-01-02T00:00", next("@daily", "2026-01-01T23:59"))
    }

    @Test
    fun invalidExpressionsReturnNull() {
        assertNull(CronExpression.parse(""))
        assertNull(CronExpression.parse("* * *"))
        assertNull(CronExpression.parse("60 * * * *"))
        assertNull(CronExpression.parse("*/0 * * * *"))
        assertNull(CronExpression.parse("5-1 * * * *"))
    }
}
