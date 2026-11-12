package com.benton.izukijs.privileged.internal

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class InputEventReaderTest {

    @Test
    fun parses64BitEventTimestampAndCodes() {
        val buffer = ByteArray(24)
        ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(0, 100L)
            putLong(8, 500_000L)
            putShort(16, 3)
            putShort(18, 0x35)
            putInt(20, 1234)
        }

        var type = -1
        var code = -1
        var value = -1
        var atMs = -1L
        InputEventReader(ByteArrayInputStream(buffer), 24, nowMs = { 999L }).readLoop { t, c, v, ms ->
            type = t
            code = c
            value = v
            atMs = ms
        }

        assertEquals(3, type)
        assertEquals(0x35, code)
        assertEquals(1234, value)
        assertEquals(100_500L, atMs)
    }

    @Test
    fun convertsRealtimeTimestampsToUptime() {
        val buffer = ByteArray(24)
        ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(0, 1_700_000_000L)
            putLong(8, 0L)
            putShort(16, 3)
            putShort(18, 0x35)
            putInt(20, 42)
        }

        var atMs = -1L
        InputEventReader(
            ByteArrayInputStream(buffer),
            24,
            nowMs = { 10_000_000L },
            nowWallMs = { 1_700_000_500_000L },
        ).readLoop { _, _, _, ms -> atMs = ms }

        // 事件发生在 500ms 前：10_000_000 - 500 = 9_500_000。
        assertEquals(9_500_000L, atMs)
    }

    @Test
    fun parses32BitEventLayout() {
        val buffer = ByteArray(16)
        ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 42)
            putInt(4, 250_000)
            putShort(8, 1)
            putShort(10, 0x14a)
            putInt(12, 1)
        }

        var type = -1
        var code = -1
        var value = -1
        var atMs = -1L
        InputEventReader(ByteArrayInputStream(buffer), 16, nowMs = { 999L }).readLoop { t, c, v, ms ->
            type = t
            code = c
            value = v
            atMs = ms
        }

        assertEquals(1, type)
        assertEquals(0x14a, code)
        assertEquals(1, value)
        assertEquals(42_250L, atMs)
    }
}
