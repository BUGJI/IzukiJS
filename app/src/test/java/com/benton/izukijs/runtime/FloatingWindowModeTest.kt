package com.benton.izukijs.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingWindowModeTest {

    @Test
    fun parsesKnownKeys() {
        FloatingWindowMode.entries.forEach { mode ->
            assertEquals(mode, FloatingWindowMode.fromKey(mode.key))
        }
    }

    @Test
    fun defaultsToExpandedWhenMissingOrUnknown() {
        assertEquals(FloatingWindowMode.EXPANDED, FloatingWindowMode.fromKey(null))
        assertEquals(FloatingWindowMode.EXPANDED, FloatingWindowMode.fromKey("unknown"))
    }
}
