package com.benton.izukijs.ui.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualScreenTouchMapperTest {

    @Test
    fun mapsCenterToScreenCenter() {
        val viewport = VirtualScreenTouchMapper.viewport(320f, 180f, 1280, 720)
        val point = VirtualScreenTouchMapper.map(160f, 90f, viewport, 1280, 720)
        assertNotNull(point)
        assertEquals(640f, point!!.x, 0.01f)
        assertEquals(360f, point.y, 0.01f)
    }

    @Test
    fun accountsForLetterboxing() {
        val viewport = VirtualScreenTouchMapper.viewport(400f, 200f, 1280, 720)
        assertEquals(0f, viewport.contentTop, 0.01f)
        assertEquals(355.56f, viewport.contentWidth, 0.05f)
        assertEquals(200f, viewport.contentHeight, 0.01f)
        assertFalse(VirtualScreenTouchMapper.inside(10f, 10f, viewport))
        assertTrue(VirtualScreenTouchMapper.inside(200f, 100f, viewport))
    }

    @Test
    fun clampsOutsideCoordinates() {
        val viewport = VirtualScreenTouchMapper.viewport(320f, 180f, 1280, 720)
        val point = VirtualScreenTouchMapper.map(-50f, 999f, viewport, 1280, 720)
        assertNotNull(point)
        assertEquals(0f, point!!.x, 0.01f)
        assertEquals(719f, point.y, 0.01f)
    }

    @Test
    fun invalidSizesReturnNull() {
        val viewport = VirtualScreenTouchMapper.viewport(0f, 0f, 0, 0)
        assertNull(VirtualScreenTouchMapper.map(0f, 0f, viewport, 0, 0))
    }
}
