package com.benton.izukijs.controller.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameSelectorTest {

    @Test
    fun picksLatestSampleAtOrBeforeAction() {
        val selector = FrameSelector(maxAgeMs = 2_000L)
        selector.add(100L, "frames/1.jpg")
        selector.add(800L, "frames/2.jpg")
        selector.add(1_500L, "frames/3.jpg")

        assertEquals("frames/2.jpg", selector.before(1_000L)?.path)
        assertEquals("frames/3.jpg", selector.before(1_500L)?.path)
        assertNull(selector.before(50L))
    }

    @Test
    fun rejectsSamplesOlderThanMaxAge() {
        val selector = FrameSelector(maxAgeMs = 1_000L)
        selector.add(100L, "frames/old.jpg")

        assertNull(selector.before(1_200L))
        assertEquals("frames/old.jpg", selector.before(1_100L)?.path)
    }

    @Test
    fun invalidateDropsPreviousPageSamples() {
        val selector = FrameSelector(maxAgeMs = 2_000L)
        selector.add(100L, "frames/page-a.jpg")

        selector.invalidate()

        assertNull(selector.before(500L))
        assertEquals(0, selector.size())
    }

    @Test
    fun findsFirstSampleAtOrAfterTimestamp() {
        val selector = FrameSelector(maxAgeMs = 2_000L)
        selector.add(100L, "frames/1.jpg")
        selector.add(900L, "frames/2.jpg")

        assertEquals("frames/2.jpg", selector.atOrAfter(220L)?.path)
        assertNull(selector.atOrAfter(1_000L))
    }

    @Test
    fun keepsOnlyRecentSamples() {
        val selector = FrameSelector(maxSamples = 3, maxAgeMs = 10_000L)
        repeat(5) { index ->
            selector.add(index * 100L, "frames/$index.jpg")
        }

        assertEquals(3, selector.size())
        assertNull(selector.before(100L))
        assertEquals("frames/4.jpg", selector.before(1_000L)?.path)
    }
}
