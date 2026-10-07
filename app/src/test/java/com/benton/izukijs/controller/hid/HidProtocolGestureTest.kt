package com.benton.izukijs.controller.hid

import org.junit.Assert.assertEquals
import org.junit.Test

class HidProtocolGestureTest {

    private fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it) }

    @Test
    fun gestureFrameLayout() {
        val frame = HidProtocol.gesture(
            flags = 0,
            points = listOf(
                HidProtocol.GestureSample(x = 360, y = 1000, deltaMs = 0),
                HidProtocol.GestureSample(x = 360, y = 500, deltaMs = 260),
            ),
        )
        assertEquals(
            "08 00 02 68 01 E8 03 00 00 68 01 F4 01 04 01",
            frame.hex(),
        )
    }
}
