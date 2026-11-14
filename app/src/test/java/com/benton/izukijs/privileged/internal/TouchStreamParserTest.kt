package com.benton.izukijs.privileged.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EV_SYN = TouchStreamParser.EV_SYN
private const val EV_KEY = TouchStreamParser.EV_KEY
private const val EV_ABS = TouchStreamParser.EV_ABS
private const val SYN_REPORT = TouchStreamParser.SYN_REPORT
private const val SYN_MT_REPORT = TouchStreamParser.SYN_MT_REPORT
private const val BTN_TOUCH = TouchStreamParser.BTN_TOUCH
private const val ABS_X = TouchStreamParser.ABS_X
private const val ABS_Y = TouchStreamParser.ABS_Y
private const val ABS_MT_SLOT = TouchStreamParser.ABS_MT_SLOT
private const val ABS_MT_POSITION_X = TouchStreamParser.ABS_MT_POSITION_X
private const val ABS_MT_POSITION_Y = TouchStreamParser.ABS_MT_POSITION_Y
private const val ABS_MT_TRACKING_ID = TouchStreamParser.ABS_MT_TRACKING_ID

class TouchStreamParserTest {

    private fun TouchStreamParser.move(
        slot: Int,
        trackingId: Int,
        x: Int,
        y: Int,
        time: Int,
    ) {
        onEvent(EV_ABS, ABS_MT_SLOT, slot, time)
        onEvent(EV_ABS, ABS_MT_TRACKING_ID, trackingId, time)
        onEvent(EV_ABS, ABS_MT_POSITION_X, x, time)
        onEvent(EV_ABS, ABS_MT_POSITION_Y, y, time)
        onEvent(EV_SYN, SYN_REPORT, 0, time)
    }

    @Test
    fun protocolBTracksPrimarySlotAndClosesOnUp() {
        val parser = TouchStreamParser()

        parser.move(slot = 0, trackingId = 1, x = 100, y = 200, time = 10)
        parser.move(slot = 0, trackingId = 1, x = 110, y = 210, time = 20)
        parser.onEvent(EV_ABS, ABS_MT_TRACKING_ID, -1, 30)
        parser.onEvent(EV_SYN, SYN_REPORT, 0, 30)

        assertEquals(1, parser.strokes.size)
        val stroke = parser.strokes.single()
        assertEquals(listOf(100 to 200, 110 to 210), stroke.points.map { it.x to it.y })
        assertEquals(30, stroke.endTMs)
    }

    @Test
    fun secondarySlotDoesNotReplacePrimaryCoordinates() {
        val parser = TouchStreamParser()

        parser.move(slot = 0, trackingId = 1, x = 100, y = 200, time = 10)
        parser.onEvent(EV_ABS, ABS_MT_SLOT, 1, 20)
        parser.onEvent(EV_ABS, ABS_MT_POSITION_X, 900, 20)
        parser.onEvent(EV_ABS, ABS_MT_POSITION_Y, 900, 20)
        parser.onEvent(EV_SYN, SYN_REPORT, 0, 20)
        parser.finish(30)

        assertEquals(listOf(100 to 200), parser.strokes.single().points.map { it.x to it.y })
    }

    @Test
    fun protocolAUsesSynMtReportFrames() {
        val parser = TouchStreamParser()

        parser.onEvent(EV_ABS, ABS_MT_POSITION_X, 100, 10)
        parser.onEvent(EV_ABS, ABS_MT_POSITION_Y, 200, 10)
        parser.onEvent(EV_SYN, SYN_MT_REPORT, 0, 10)
        parser.onEvent(EV_SYN, SYN_REPORT, 0, 10)
        parser.onEvent(EV_ABS, ABS_MT_POSITION_X, 120, 20)
        parser.onEvent(EV_ABS, ABS_MT_POSITION_Y, 220, 20)
        parser.onEvent(EV_SYN, SYN_MT_REPORT, 0, 20)
        parser.onEvent(EV_SYN, SYN_REPORT, 0, 20)
        parser.finish(30)

        assertEquals(
            listOf(100 to 200, 120 to 220),
            parser.strokes.single().points.map { it.x to it.y },
        )
    }

    @Test
    fun singleTouchUsesBtnTouchAndAbsCoordinates() {
        val parser = TouchStreamParser()

        parser.onEvent(EV_KEY, BTN_TOUCH, 1, 10)
        parser.onEvent(EV_ABS, ABS_X, 50, 10)
        parser.onEvent(EV_ABS, ABS_Y, 60, 10)
        parser.onEvent(EV_SYN, SYN_REPORT, 0, 10)
        parser.onEvent(EV_ABS, ABS_X, 55, 20)
        parser.onEvent(EV_ABS, ABS_Y, 65, 20)
        parser.onEvent(EV_SYN, SYN_REPORT, 0, 20)
        parser.onEvent(EV_KEY, BTN_TOUCH, 0, 30)
        parser.onEvent(EV_SYN, SYN_REPORT, 0, 30)

        assertEquals(
            listOf(50 to 60, 55 to 65),
            parser.strokes.single().points.map { it.x to it.y },
        )
    }

    @Test
    fun duplicateCoordinatesAreCollapsed() {
        val parser = TouchStreamParser()

        parser.move(slot = 0, trackingId = 1, x = 100, y = 200, time = 10)
        parser.move(slot = 0, trackingId = 1, x = 100, y = 200, time = 20)
        parser.onEvent(EV_ABS, ABS_MT_TRACKING_ID, -1, 30)
        parser.onEvent(EV_SYN, SYN_REPORT, 0, 30)

        assertEquals(1, parser.strokes.single().points.size)
    }

    @Test
    fun streamingModeEmitsClosedStrokesWithoutRetainingHistory() {
        val parser = TouchStreamParser()
        parser.streaming = true
        val emitted = mutableListOf<TouchStroke>()
        parser.onStrokeClosed = { emitted += it }

        parser.move(slot = 0, trackingId = 1, x = 10, y = 20, time = 10)
        parser.onEvent(EV_ABS, ABS_MT_TRACKING_ID, -1, 20)
        parser.onEvent(EV_SYN, SYN_REPORT, 0, 20)

        assertTrue(parser.strokes.isEmpty())
        assertEquals(1, emitted.size)
        assertEquals(10, emitted.single().points.single().x)
    }

    @Test
    fun finishClosesOpenStroke() {
        val parser = TouchStreamParser()
        parser.move(slot = 0, trackingId = 1, x = 10, y = 20, time = 10)

        parser.finish(15)

        assertEquals(1, parser.strokes.size)
        assertEquals(15, parser.strokes.single().endTMs)
    }

    @Test
    fun strokeCountIsBounded() {
        val parser = TouchStreamParser()
        repeat(TouchStreamParser.MAX_STROKES + 5) { index ->
            parser.move(slot = 0, trackingId = 1, x = index, y = index, time = index * 2)
            parser.onEvent(EV_ABS, ABS_MT_TRACKING_ID, -1, index * 2 + 1)
            parser.onEvent(EV_SYN, SYN_REPORT, 0, index * 2 + 1)
        }

        assertEquals(TouchStreamParser.MAX_STROKES, parser.strokes.size)
    }

    @Test
    fun zeroSizeFrameDoesNotCreateStroke() {
        val parser = TouchStreamParser()

        parser.onEvent(EV_ABS, ABS_MT_SLOT, 0, 0)
        parser.onEvent(EV_ABS, ABS_MT_TRACKING_ID, 1, 0)
        parser.onEvent(EV_SYN, SYN_REPORT, 0, 0)
        parser.finish(1)

        assertTrue(parser.strokes.isEmpty())
    }
}
