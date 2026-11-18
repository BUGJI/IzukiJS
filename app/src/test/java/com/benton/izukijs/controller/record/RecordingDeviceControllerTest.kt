package com.benton.izukijs.controller.record

import com.benton.izukijs.controller.DeviceController
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDeviceControllerTest {

    @Test
    fun displayTargetCallsAreDelegatedAndRecorded() {
        val inner = FakeDisplayController()
        val recorder = FakeRecorder()
        val proxy = RecordingDeviceController(inner, recorder)

        assertTrue(proxy.supportsDisplayTarget())

        assertTrue(proxy.clickForDisplay(11f, 22f, 0))
        assertEquals(11f, inner.click!!.first)
        assertEquals(22f, inner.click!!.second)
        assertEquals(0, inner.click!!.third)
        assertEquals(11, (recorder.events.last() as ClickEvent).x)

        assertTrue(proxy.longClickForDisplay(33f, 44f, 750L, 3))
        assertEquals(3, inner.longClick!!.third)
        val longClick = recorder.events.last() as LongClickEvent
        assertEquals(750L, longClick.durationMs)

        assertTrue(proxy.swipeForDisplay(1f, 2f, 3f, 4f, 120L, 2))
        assertEquals(2, inner.swipe!!.last())
        val swipe = recorder.events.last() as SwipeEvent
        assertEquals(1, swipe.x1)
        assertEquals(4, swipe.y2)
    }

    @Test
    fun displayTargetFailureIsPropagated() {
        val inner = FakeDisplayController().apply { success = false }
        val proxy = RecordingDeviceController(inner, FakeRecorder())

        assertFalse(proxy.clickForDisplay(1f, 1f, 0))
    }

    private class FakeDisplayController : DeviceController {
        override val mode: ControlMode = ControlMode.SHIZUKU

        override fun capabilities(): Set<Capability> =
            setOf(Capability.GESTURE, Capability.SCREENSHOT)

        override fun isReady(): Boolean = true

        override fun supportsDisplayTarget(): Boolean = true

        var success = true
        var click: Triple<Float, Float, Int>? = null
        var longClick: Triple<Float, Float, Int>? = null
        var swipe: List<Int>? = null

        override fun clickForDisplay(x: Float, y: Float, displayId: Int): Boolean {
            click = Triple(x, y, displayId)
            return success
        }

        override fun longClickForDisplay(
            x: Float,
            y: Float,
            durationMs: Long,
            displayId: Int,
        ): Boolean {
            longClick = Triple(x, y, displayId)
            return success
        }

        override fun swipeForDisplay(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            durationMs: Long,
            displayId: Int,
        ): Boolean {
            swipe = listOf(
                x1.toInt(),
                y1.toInt(),
                x2.toInt(),
                y2.toInt(),
                durationMs.toInt(),
                displayId,
            )
            return success
        }
    }

    private class FakeRecorder : EventRecorder {
        val events = mutableListOf<RecordEvent>()

        override fun record(event: RecordEvent): Long {
            events += event
            return events.size.toLong()
        }

        override fun timestampOf(wallMs: Long): Long = wallMs
    }
}
