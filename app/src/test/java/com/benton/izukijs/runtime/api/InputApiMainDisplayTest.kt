package com.benton.izukijs.runtime.api

import com.benton.izukijs.controller.DeviceController
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `target=main` 的主屏注入：能定向 display 的后端显式传 displayId，其余后端走普通点击。
 *
 * 这是虚拟屏激活时主屏输入被误判为不可用的回归测试。
 */
class InputApiMainDisplayTest {

    private data class Click(val x: Float, val y: Float)
    private data class LongClick(val x: Float, val y: Float, val durationMs: Long)
    private data class DisplayClick(val x: Float, val y: Float, val displayId: Int)
    private data class DisplayLongClick(
        val x: Float,
        val y: Float,
        val durationMs: Long,
        val displayId: Int,
    )

    private class RecordingController(
        private val displayTarget: Boolean,
        private val result: Boolean = true,
    ) : DeviceController {
        override val mode: ControlMode = ControlMode.SHIZUKU

        override fun capabilities(): Set<Capability> = setOf(Capability.GESTURE)

        override fun isReady(): Boolean = true

        override fun supportsDisplayTarget(): Boolean = displayTarget

        val clicks = mutableListOf<Click>()
        val longClicks = mutableListOf<LongClick>()
        val displayClicks = mutableListOf<DisplayClick>()
        val displayLongClicks = mutableListOf<DisplayLongClick>()

        override fun click(x: Float, y: Float): Boolean {
            clicks += Click(x, y)
            return result
        }

        override fun longClick(x: Float, y: Float, durationMs: Long): Boolean {
            longClicks += LongClick(x, y, durationMs)
            return result
        }

        override fun clickForDisplay(x: Float, y: Float, displayId: Int): Boolean {
            displayClicks += DisplayClick(x, y, displayId)
            return result
        }

        override fun longClickForDisplay(
            x: Float,
            y: Float,
            durationMs: Long,
            displayId: Int,
        ): Boolean {
            displayLongClicks += DisplayLongClick(x, y, durationMs, displayId)
            return result
        }
    }

    @Test
    fun displayTargetBackendReceivesExplicitMainDisplay() {
        val controller = RecordingController(displayTarget = true)

        assertTrue(injectOnMainDisplay(controller, 10f, 20f))
        assertTrue(injectOnMainDisplay(controller, 30f, 40f, 700L))

        assertEquals(listOf(DisplayClick(10f, 20f, 0)), controller.displayClicks)
        assertEquals(listOf(DisplayLongClick(30f, 40f, 700L, 0)), controller.displayLongClicks)
        assertTrue(controller.clicks.isEmpty())
        assertTrue(controller.longClicks.isEmpty())
    }

    @Test
    fun nonDisplayTargetBackendUsesPlainMacros() {
        val controller = RecordingController(displayTarget = false)

        assertTrue(injectOnMainDisplay(controller, 10f, 20f))
        assertTrue(injectOnMainDisplay(controller, 30f, 40f, 700L))

        assertEquals(listOf(Click(10f, 20f)), controller.clicks)
        assertEquals(listOf(LongClick(30f, 40f, 700L)), controller.longClicks)
        assertTrue(controller.displayClicks.isEmpty())
        assertTrue(controller.displayLongClicks.isEmpty())
    }

    @Test
    fun displayIdCanBeOverriddenByCaller() {
        val controller = RecordingController(displayTarget = true)

        injectOnMainDisplay(controller, 1f, 2f, displayId = 3)

        assertEquals(listOf(DisplayClick(1f, 2f, 3)), controller.displayClicks)
    }

    @Test
    fun controllerResultIsPropagated() {
        val failing = RecordingController(displayTarget = true, result = false)
        val failingPlain = RecordingController(displayTarget = false, result = false)

        assertFalse(injectOnMainDisplay(failing, 1f, 2f))
        assertFalse(injectOnMainDisplay(failingPlain, 1f, 2f, 500L))
    }
}
