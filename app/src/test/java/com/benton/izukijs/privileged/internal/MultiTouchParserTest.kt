package com.benton.izukijs.privileged.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MultiTouchParserTest {

    @Test
    fun groupsTwoContactsIntoOneGesture() {
        val parser = MultiTouchStreamParser()
        var gesture: TouchGesture? = null
        parser.onGestureClosed = { gesture = it }

        parser.onEvent(EV_ABS, ABS_MT_SLOT, 0, 0)
        parser.onEvent(EV_ABS, ABS_MT_TRACKING_ID, 1, 0)
        parser.onEvent(EV_ABS, ABS_MT_POSITION_X, 100, 0)
        parser.onEvent(EV_ABS, ABS_MT_POSITION_Y, 200, 0)
        parser.onEvent(EV_SYN, SYN_REPORT, 0, 0)

        parser.onEvent(EV_ABS, ABS_MT_SLOT, 1, 10)
        parser.onEvent(EV_ABS, ABS_MT_TRACKING_ID, 2, 10)
        parser.onEvent(EV_ABS, ABS_MT_POSITION_X, 300, 10)
        parser.onEvent(EV_ABS, ABS_MT_POSITION_Y, 400, 10)
        parser.onEvent(EV_SYN, SYN_REPORT, 0, 10)

        parser.onEvent(EV_ABS, ABS_MT_SLOT, 0, 20)
        parser.onEvent(EV_ABS, ABS_MT_POSITION_X, 110, 20)
        parser.onEvent(EV_ABS, ABS_MT_POSITION_Y, 210, 20)
        parser.onEvent(EV_SYN, SYN_REPORT, 0, 20)

        parser.onEvent(EV_ABS, ABS_MT_SLOT, 1, 30)
        parser.onEvent(EV_ABS, ABS_MT_POSITION_X, 290, 30)
        parser.onEvent(EV_ABS, ABS_MT_POSITION_Y, 390, 30)
        parser.onEvent(EV_SYN, SYN_REPORT, 0, 30)

        parser.onEvent(EV_ABS, ABS_MT_SLOT, 0, 40)
        parser.onEvent(EV_ABS, ABS_MT_TRACKING_ID, -1, 40)
        parser.onEvent(EV_ABS, ABS_MT_SLOT, 1, 50)
        parser.onEvent(EV_ABS, ABS_MT_TRACKING_ID, -1, 50)

        val result = gesture
        assertNotNull(result)
        assertEquals(2, result!!.pointerCount)
        assertEquals(2, result.strokes.size)
        assertEquals(100, result.strokes[0].points.first().x)
        assertEquals(290, result.strokes[1].points.last().x)
    }

    @Test
    fun closesAllOnButtonUp() {
        val parser = MultiTouchStreamParser()
        var gesture: TouchGesture? = null
        parser.onGestureClosed = { gesture = it }

        parser.onEvent(EV_ABS, ABS_MT_SLOT, 0, 0)
        parser.onEvent(EV_ABS, ABS_MT_TRACKING_ID, 1, 0)
        parser.onEvent(EV_ABS, ABS_MT_POSITION_X, 10, 0)
        parser.onEvent(EV_ABS, ABS_MT_POSITION_Y, 20, 0)
        parser.onEvent(EV_SYN, SYN_REPORT, 0, 0)
        parser.onEvent(EV_KEY, BTN_TOUCH, 0, 5)

        assertNotNull(gesture)
        assertEquals(1, gesture!!.pointerCount)
        assertEquals(1, gesture.strokes.size)
    }

    private companion object {
        const val EV_SYN = 0x00
        const val EV_KEY = 0x01
        const val EV_ABS = 0x03
        const val SYN_REPORT = 0x00
        const val BTN_TOUCH = 0x14a
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39
    }
}
