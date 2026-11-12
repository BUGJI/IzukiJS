package com.benton.izukijs.controller.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingReplayTest {

    @Test
    fun strictConsumesScreencapThenAction() {
        val replay = RecordingReplay(
            listOf(
                ScreenshotEvent(0, "frames/1.jpg", 100, 200, seq = 1),
                ClickEvent(10, 5, 6, true, seq = 2),
            ),
            ReplayOptions(strictness = ReplayStrictness.STRICT),
        )

        assertNotNull(replay.nextScreencap())
        assertNotNull(replay.expectAction(RecordActionKind.CLICK))
        val report = replay.finish()

        assertTrue(report.ok)
        assertEquals(2, report.consumed)
    }

    @Test
    fun strictFailsWhenActionComesBeforeScreencap() {
        val replay = RecordingReplay(
            listOf(ClickEvent(10, 5, 6, true, seq = 1)),
            ReplayOptions(strictness = ReplayStrictness.STRICT),
        )

        assertNull(replay.nextScreencap())
        val report = replay.finish()

        assertFalse(report.ok)
        assertTrue(report.mismatches.any { it.message.contains("期望截图") })
    }

    @Test
    fun strictFailsOnUnconsumedEvents() {
        val replay = RecordingReplay(
            listOf(ClickEvent(10, 5, 6, true, seq = 1)),
            ReplayOptions(strictness = ReplayStrictness.STRICT),
        )

        val report = replay.finish()

        assertFalse(report.ok)
        assertTrue(report.mismatches.any { it.message.contains("未消费") })
    }

    @Test
    fun tolerantSkipsUserActions() {
        val replay = RecordingReplay(
            listOf(
                UserActionEvent(0, UserActionKind.TAP, x = 1, y = 2, seq = 1),
                ScreenshotEvent(10, "frames/1.jpg", 100, 200, seq = 2),
            ),
        )

        assertNotNull(replay.nextScreencap())
        assertTrue(replay.notes.any { it.contains("跳过用户动作") })
    }

    @Test
    fun tolerantReportsMissingAction() {
        val replay = RecordingReplay(listOf(ScreenshotEvent(0, "frames/1.jpg", 1, 1, seq = 1)))

        assertNull(replay.expectAction(RecordActionKind.CLICK))
        assertTrue(replay.mismatches.any { it.message.contains("未找到动作 CLICK") })
    }
}
