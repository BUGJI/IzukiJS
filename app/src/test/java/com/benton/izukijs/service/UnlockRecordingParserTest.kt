package com.benton.izukijs.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockRecordingParserTest {

    @Test
    fun parsesTouchDeviceRanges() {
        val device = UnlockRecordingParser.parseTouchDevice(
            """
            add device 4: /dev/input/event2
              name:     "NVTCapacitiveTouchScreen"
              events:
                ABS (0003): ABS_MT_POSITION_X     : value 0, min 0, max 719, fuzz 0, flat 0, resolution 0
                            ABS_MT_POSITION_Y     : value 0, min 0, max 1519, fuzz 0, flat 0, resolution 0
            """.trimIndent(),
        )

        assertNotNull(device)
        assertEquals("/dev/input/event2", device!!.path)
        assertEquals(719, device.maxX)
        assertEquals(1519, device.maxY)
        assertEquals(720, device.rangeX)
        assertEquals(1520, device.rangeY)
        assertFalse(device.hasStylus)
    }

    @Test
    fun ignoresStylusAndPicksTouchscreen() {
        val devices = UnlockRecordingParser.parseTouchDevices(
            """
            add device 1: /dev/input/event0
              name:     "pen"
              events:
                KEY (0001): BTN_TOOL_PEN BTN_TOUCH
                ABS (0003): ABS_MT_POSITION_X     : value 0, min 0, max 4095, fuzz 0, flat 0, resolution 0
                            ABS_MT_POSITION_Y     : value 0, min 0, max 4095, fuzz 0, flat 0, resolution 0
            add device 2: /dev/input/event2
              name:     "NVTCapacitiveTouchScreen"
              events:
                KEY (0001): BTN_TOUCH
                ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                            ABS_MT_POSITION_X     : value 0, min 0, max 719, fuzz 0, flat 0, resolution 0
                            ABS_MT_POSITION_Y     : value 0, min 0, max 1519, fuzz 0, flat 0, resolution 0
                            ABS_MT_TRACKING_ID    : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0
              input props:
                INPUT_PROP_DIRECT
            """.trimIndent(),
        )

        assertEquals(1, devices.size)
        assertEquals("/dev/input/event2", devices[0].path)
        assertTrue(devices[0].protocolB)
        assertTrue(devices[0].isDirect)
    }

    @Test
    fun parsesLabeledTouchIntoStrokes() {
        val device = TouchInputDevice("/dev/input/event2", 719, 1519)
        val strokes = UnlockRecordingParser.parseStrokes(
            """
            [   1.000000] /dev/input/event2: EV_ABS ABS_MT_TRACKING_ID 00000001
            [   1.010000] /dev/input/event2: EV_ABS ABS_MT_POSITION_X 00000064
            [   1.020000] /dev/input/event2: EV_ABS ABS_MT_POSITION_Y 000000c8
            [   1.030000] /dev/input/event2: EV_SYN SYN_REPORT 00000000
            [   1.100000] /dev/input/event2: EV_ABS ABS_MT_POSITION_X 0000012c
            [   1.110000] /dev/input/event2: EV_ABS ABS_MT_POSITION_Y 00000190
            [   1.120000] /dev/input/event2: EV_SYN SYN_REPORT 00000000
            [   1.200000] /dev/input/event2: EV_ABS ABS_MT_TRACKING_ID ffffffff
            [   1.210000] /dev/input/event2: EV_SYN SYN_REPORT 00000000
            """.trimIndent(),
            device,
        )

        assertEquals(1, strokes.size)
        val stroke = strokes[0]
        assertTrue(stroke.points.size >= 2)
        assertEquals(100f / 720f, stroke.points[0].x, 0.001f)
        assertEquals(200f / 1520f, stroke.points[0].y, 0.001f)
        assertEquals(200L, stroke.durationMs)
    }

    @Test
    fun parsesHexTouchAndMultipleTaps() {
        val device = TouchInputDevice("/dev/input/event2", 999, 1999)
        val strokes = UnlockRecordingParser.parseStrokes(
            """
            [   2.000000] /dev/input/event2: 0003 0039 00000001
            [   2.010000] /dev/input/event2: 0003 0035 00000190
            [   2.020000] /dev/input/event2: 0003 0036 00000320
            [   2.030000] /dev/input/event2: 0000 0000 00000000
            [   2.040000] /dev/input/event2: 0003 0039 ffffffff
            [   2.050000] /dev/input/event2: 0000 0000 00000000
            [   3.000000] /dev/input/event2: 0003 0039 00000002
            [   3.010000] /dev/input/event2: 0003 0035 00000258
            [   3.020000] /dev/input/event2: 0003 0036 000004b0
            [   3.030000] /dev/input/event2: 0000 0000 00000000
            [   3.040000] /dev/input/event2: 0003 0039 ffffffff
            [   3.050000] /dev/input/event2: 0000 0000 00000000
            """.trimIndent(),
            device,
        )

        assertEquals(2, strokes.size)
        assertEquals(0.4f, strokes[0].points[0].x, 0.001f)
        assertEquals(0.4f, strokes[0].points[0].y, 0.001f)
        assertEquals(0.6f, strokes[1].points[0].x, 0.001f)
        assertEquals(0.6f, strokes[1].points[0].y, 0.001f)
        assertEquals(1000L, strokes[1].startTimeMs)
    }

    @Test
    fun parsesSingleDeviceOutputWithoutPathPrefix() {
        val device = TouchInputDevice(
            path = "/dev/input/event2",
            maxX = 719,
            maxY = 1519,
            hasSlot = true,
            hasTrackingId = true,
            hasBtnTouch = true,
            isDirect = true,
        )
        val strokes = UnlockRecordingParser.parseStrokes(
            """
            [   1.000000] EV_ABS       ABS_MT_SLOT           00000000
            [   1.000000] EV_ABS       ABS_MT_TRACKING_ID    000003e7
            [   1.000000] EV_ABS       ABS_MT_POSITION_X     00000168
            [   1.000000] EV_ABS       ABS_MT_POSITION_Y     000002f8
            [   1.000000] EV_KEY       BTN_TOUCH             DOWN
            [   1.000000] EV_SYN       SYN_REPORT            00000000
            [   1.080000] EV_ABS       ABS_MT_POSITION_X     00000169
            [   1.080000] EV_ABS       ABS_MT_POSITION_Y     000002f9
            [   1.080000] EV_SYN       SYN_REPORT            00000000
            [   1.160000] EV_ABS       ABS_MT_TRACKING_ID    ffffffff
            [   1.160000] EV_KEY       BTN_TOUCH             UP
            [   1.160000] EV_SYN       SYN_REPORT            00000000
            """.trimIndent(),
            device,
        )

        assertEquals(1, strokes.size)
        assertTrue(strokes[0].points.size >= 2)
        assertEquals(360f / 720f, strokes[0].points[0].x, 0.001f)
        assertEquals(760f / 1520f, strokes[0].points[0].y, 0.001f)
        assertEquals(160L, strokes[0].durationMs)
    }

    @Test
    fun parsesProtocolAWithBtnTouch() {
        val device = TouchInputDevice(
            path = "/dev/input/event2",
            maxX = 999,
            maxY = 1999,
            hasBtnTouch = true,
        )
        val strokes = UnlockRecordingParser.parseStrokes(
            """
            [   1.000000] /dev/input/event2: EV_KEY BTN_TOUCH 00000001
            [   1.010000] /dev/input/event2: EV_ABS ABS_X 00000064
            [   1.020000] /dev/input/event2: EV_ABS ABS_Y 000000c8
            [   1.030000] /dev/input/event2: EV_SYN SYN_REPORT 00000000
            [   1.100000] /dev/input/event2: EV_ABS ABS_X 0000012c
            [   1.110000] /dev/input/event2: EV_ABS ABS_Y 00000190
            [   1.120000] /dev/input/event2: EV_SYN SYN_REPORT 00000000
            [   1.200000] /dev/input/event2: EV_KEY BTN_TOUCH 00000000
            [   1.210000] /dev/input/event2: EV_SYN SYN_REPORT 00000000
            """.trimIndent(),
            device,
        )

        assertEquals(1, strokes.size)
        assertEquals(2, strokes[0].points.size)
        assertEquals(100f / 1000f, strokes[0].points[0].x, 0.001f)
        assertEquals(200f / 2000f, strokes[0].points[0].y, 0.001f)
        assertEquals(200L, strokes[0].durationMs)
    }

    @Test
    fun followsOnlyPrimarySlotWhenSecondFingerAppears() {
        val device = TouchInputDevice(
            path = "/dev/input/event2",
            maxX = 999,
            maxY = 1999,
            hasSlot = true,
            hasTrackingId = true,
            hasBtnTouch = true,
            isDirect = true,
        )
        val strokes = UnlockRecordingParser.parseStrokes(
            """
            [   1.000000] /dev/input/event2: EV_ABS ABS_MT_SLOT 00000000
            [   1.010000] /dev/input/event2: EV_ABS ABS_MT_TRACKING_ID 00000001
            [   1.020000] /dev/input/event2: EV_ABS ABS_MT_POSITION_X 00000064
            [   1.030000] /dev/input/event2: EV_ABS ABS_MT_POSITION_Y 000000c8
            [   1.040000] /dev/input/event2: EV_SYN SYN_REPORT 00000000
            [   1.100000] /dev/input/event2: EV_ABS ABS_MT_SLOT 00000001
            [   1.110000] /dev/input/event2: EV_ABS ABS_MT_TRACKING_ID 00000002
            [   1.120000] /dev/input/event2: EV_ABS ABS_MT_POSITION_X 000001f4
            [   1.130000] /dev/input/event2: EV_ABS ABS_MT_POSITION_Y 00000320
            [   1.140000] /dev/input/event2: EV_SYN SYN_REPORT 00000000
            [   1.200000] /dev/input/event2: EV_ABS ABS_MT_SLOT 00000000
            [   1.210000] /dev/input/event2: EV_ABS ABS_MT_POSITION_X 0000012c
            [   1.220000] /dev/input/event2: EV_ABS ABS_MT_POSITION_Y 00000190
            [   1.230000] /dev/input/event2: EV_SYN SYN_REPORT 00000000
            [   1.300000] /dev/input/event2: EV_ABS ABS_MT_TRACKING_ID ffffffff
            [   1.310000] /dev/input/event2: EV_SYN SYN_REPORT 00000000
            """.trimIndent(),
            device,
        )

        assertEquals(1, strokes.size)
        assertEquals(2, strokes[0].points.size)
        assertEquals(100f / 1000f, strokes[0].points[0].x, 0.001f)
        assertEquals(300f / 1000f, strokes[0].points[1].x, 0.001f)
    }

    @Test
    fun normalizesUsingDeviceMinAndMax() {
        val device = TouchInputDevice(
            path = "/dev/input/event2",
            maxX = 199,
            maxY = 199,
            minX = 100,
            minY = 100,
            hasTrackingId = true,
        )
        val strokes = UnlockRecordingParser.parseStrokes(
            """
            [   1.000000] /dev/input/event2: 0003 0039 00000001
            [   1.010000] /dev/input/event2: 0003 0035 00000064
            [   1.020000] /dev/input/event2: 0003 0036 00000064
            [   1.030000] /dev/input/event2: 0000 0000 00000000
            [   1.100000] /dev/input/event2: 0003 0035 000000c7
            [   1.110000] /dev/input/event2: 0003 0036 000000c7
            [   1.120000] /dev/input/event2: 0000 0000 00000000
            [   1.200000] /dev/input/event2: 0003 0039 ffffffff
            """.trimIndent(),
            device,
        )

        assertEquals(1, strokes.size)
        assertEquals(0f, strokes[0].points[0].x, 0.001f)
        assertEquals(0.99f, strokes[0].points[1].x, 0.001f)
    }
}
