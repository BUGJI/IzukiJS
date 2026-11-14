package com.benton.izukijs.controller.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTimelineTest {

    private fun tap(
        timestamp: Long,
        beforeFrame: String? = null,
        afterFrame: String? = null,
    ): UserActionEvent = UserActionEvent(
        timestamp = timestamp,
        kind = UserActionKind.TAP,
        x = 10,
        y = 20,
        beforeFrame = beforeFrame,
        afterFrame = afterFrame,
    )

    @Test
    fun defaultTypesHidePeriodicScreenshots() {
        val events = listOf(
            tap(0L, beforeFrame = "frames/before.jpg", afterFrame = "frames/after.jpg"),
            ScreenshotEvent(700L, "frames/periodic.jpg", 100, 200),
        )

        val page = RecordingTimeline.page(events)

        assertEquals(1, page.total)
        assertEquals("tap", page.items.single().optString("type"))
    }

    @Test
    fun actionRowCarriesBeforeAndAfterFrames() {
        val row = RecordingTimeline.row(
            tap(0L, beforeFrame = "frames/before.jpg", afterFrame = "frames/after.jpg"),
        )!!

        assertEquals("frames/before.jpg", row.optString("frame"))
        assertEquals("frames/before.jpg", row.optString("before_frame"))
        assertEquals("frames/after.jpg", row.optString("after_frame"))
    }

    @Test
    fun explicitScreencapTypeIncludesFrames() {
        val events = listOf(
            tap(0L),
            ScreenshotEvent(700L, "frames/periodic.jpg", 100, 200),
        )

        val page = RecordingTimeline.page(
            events = events,
            types = RecordingTimeline.DEFAULT_TYPES + RecordingTimeline.TYPE_SCREENCAP,
        )

        assertEquals(2, page.total)
        assertTrue(page.items.any { it.optString("type") == "screencap" })
    }

    @Test
    fun paginationReportsMoreAndNextOffset() {
        val events = (0 until 5).map { tap(it * 100L) }

        val first = RecordingTimeline.page(events, offset = 0, limit = 2)
        assertTrue(first.hasMore)
        assertEquals(2, first.nextOffset)
        assertEquals(5, first.total)

        val last = RecordingTimeline.page(events, offset = 4, limit = 2)
        assertFalse(last.hasMore)
        assertNull(last.nextOffset)
        assertEquals(1, last.items.size)
    }

    @Test
    fun sessionAndContextRowsAreNotTimelineRows() {
        assertNull(RecordingTimeline.row(SessionStartEvent()))
        assertNull(RecordingTimeline.row(ContextEvent(timestamp = 10L, refSeq = 1L)))
        assertNotNull(RecordingTimeline.row(ScreenshotEvent(10L, "frames/a.jpg", 1, 1)))
    }
}
