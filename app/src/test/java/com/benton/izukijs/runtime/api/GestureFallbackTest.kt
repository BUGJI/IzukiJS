package com.benton.izukijs.runtime.api

import com.benton.izukijs.controller.DeviceController
import com.benton.izukijs.controller.GesturePoint
import com.benton.izukijs.controller.GestureStroke
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证手势降级路径：同点短段下发为点击（70~180ms），更长才走长按。
 * 这是此前「脚本双击点赞变成两次长按」的回归测试。
 */
class GestureFallbackTest {

    private data class Swipe(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val durationMs: Long,
    )

    private data class LongClick(val x: Float, val y: Float, val durationMs: Long)

    private class RecordingController : DeviceController {
        override val mode: ControlMode = ControlMode.SHIZUKU

        override fun capabilities(): Set<Capability> = setOf(Capability.GESTURE)

        override fun isReady(): Boolean = true

        val swipes = mutableListOf<Swipe>()
        val longClicks = mutableListOf<LongClick>()

        override fun swipe(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            durationMs: Long,
        ): Boolean {
            swipes.add(Swipe(x1, y1, x2, y2, durationMs))
            return true
        }

        override fun longClick(x: Float, y: Float, durationMs: Long): Boolean {
            longClicks.add(LongClick(x, y, durationMs))
            return true
        }
    }

    private fun stroke(vararg points: Triple<Float, Float, Long>): GestureStroke =
        GestureStroke(points.map { GesturePoint(it.first, it.second, it.third) })

    @Test
    fun shortSamePointBecomesTapNotLongPress() {
        val controller = RecordingController()

        val ok = applyGestureFallback(
            controller,
            listOf(stroke(Triple(100f, 200f, 0L), Triple(100f, 200f, 15L))),
        )

        assertTrue(ok)
        assertTrue("短同点段不应触发长按", controller.longClicks.isEmpty())
        assertEquals(listOf(Swipe(100f, 200f, 100f, 200f, 70L)), controller.swipes)
    }

    @Test
    fun tapDurationClampedToRange() {
        val controller = RecordingController()

        applyGestureFallback(
            controller,
            listOf(stroke(Triple(100f, 200f, 0L), Triple(100f, 200f, 120L))),
        )

        assertEquals(listOf(Swipe(100f, 200f, 100f, 200f, 120L)), controller.swipes)
    }

    @Test
    fun boundaryAtTapMaxStaysTap() {
        val controller = RecordingController()

        applyGestureFallback(
            controller,
            listOf(stroke(Triple(100f, 200f, 0L), Triple(100f, 200f, 180L))),
        )

        assertTrue(controller.longClicks.isEmpty())
        assertEquals(listOf(Swipe(100f, 200f, 100f, 200f, 180L)), controller.swipes)
    }

    @Test
    fun longSamePointBecomesHold() {
        val controller = RecordingController()

        applyGestureFallback(
            controller,
            listOf(stroke(Triple(100f, 200f, 0L), Triple(100f, 200f, 700L))),
        )

        assertTrue("长同点段不应下发 swipe", controller.swipes.isEmpty())
        assertEquals(listOf(LongClick(100f, 200f, 700L)), controller.longClicks)
    }

    @Test
    fun movingSegmentIsSwipe() {
        val controller = RecordingController()

        applyGestureFallback(
            controller,
            listOf(stroke(Triple(10f, 20f, 0L), Triple(30f, 60f, 260L))),
        )

        assertTrue(controller.longClicks.isEmpty())
        assertEquals(listOf(Swipe(10f, 20f, 30f, 60f, 260L)), controller.swipes)
    }

    @Test
    fun doubleTapStrokeBothBecomeTaps() {
        val controller = RecordingController()
        val tap = stroke(Triple(540f, 1600f, 0L), Triple(540f, 1600f, 15L))

        applyGestureFallback(controller, listOf(tap, tap))

        assertTrue(controller.longClicks.isEmpty())
        assertEquals(
            listOf(
                Swipe(540f, 1600f, 540f, 1600f, 70L),
                Swipe(540f, 1600f, 540f, 1600f, 70L),
            ),
            controller.swipes,
        )
    }

    @Test
    fun swipeThenHoldKeepsHoldSemantics() {
        val controller = RecordingController()

        applyGestureFallback(
            controller,
            listOf(
                stroke(
                    Triple(540f, 2100f, 0L),
                    Triple(540f, 1200f, 260L),
                    Triple(540f, 1200f, 960L),
                ),
            ),
        )

        assertEquals(listOf(Swipe(540f, 2100f, 540f, 1200f, 260L)), controller.swipes)
        assertEquals(listOf(LongClick(540f, 1200f, 700L)), controller.longClicks)
    }
}
