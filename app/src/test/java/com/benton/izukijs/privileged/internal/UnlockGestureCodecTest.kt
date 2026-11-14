package com.benton.izukijs.privileged.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockGestureCodecTest {

    private val screen = ScreenGeometry(width = 1000, height = 2000, rotation = 1)

    private fun tapStroke(tMs: Int): TouchStroke =
        TouchStroke(
            points = listOf(TouchPoint(x = 500, y = 1000, tMs = tMs)),
            endTMs = tMs + 10,
        )

    @Test
    fun buildsPatternWithRotationAndScreenSize() {
        val pattern = UnlockGestureCodec.build(listOf(tapStroke(100)), screen)

        assertEquals(1, pattern.rotation)
        assertEquals(1000, pattern.screenWidth)
        assertEquals(2000, pattern.screenHeight)
        assertEquals(1, pattern.strokes.size)
    }

    @Test
    fun smallMovementIsTreatedAsTap() {
        val stroke = TouchStroke(
            points = listOf(
                TouchPoint(x = 500, y = 1000, tMs = 100),
                TouchPoint(x = 510, y = 1010, tMs = 110),
            ),
            endTMs = 120,
        )

        val built = UnlockGestureCodec.build(listOf(stroke), screen).strokes.single()

        assertEquals(1, built.points.size)
        assertEquals(0.5f, built.points.single().x, 0.0001f)
        assertEquals(0.5f, built.points.single().y, 0.0001f)
        assertEquals(0L, built.startTimeMs)
        assertEquals(20L, built.durationMs)
    }

    @Test
    fun movementNormalizesAndKeepsRelativeTimes() {
        val stroke = TouchStroke(
            points = listOf(
                TouchPoint(0, 0, tMs = 100),
                TouchPoint(100, 100, tMs = 110),
                TouchPoint(500, 1000, tMs = 120),
            ),
            endTMs = 130,
        )

        val built = UnlockGestureCodec.build(listOf(stroke), screen).strokes.single()

        assertEquals(3, built.points.size)
        assertEquals(0L, built.startTimeMs)
        assertEquals(30L, built.durationMs)
        assertEquals(0f, built.points.first().x, 0.0001f)
        assertEquals(0.5f, built.points.last().x, 0.0001f)
        assertEquals(0.5f, built.points.last().y, 0.0001f)
        assertEquals(0L, built.points.first().timeMs)
        assertEquals(20L, built.points.last().timeMs)
    }

    @Test
    fun laterStrokesUseRecordingStartAsBase() {
        val pattern = UnlockGestureCodec.build(
            listOf(tapStroke(1_000), tapStroke(2_000)),
            screen,
        )

        assertEquals(0L, pattern.strokes[0].startTimeMs)
        assertEquals(1_000L, pattern.strokes[1].startTimeMs)
    }

    @Test
    fun durationIsAtLeastOneMillisecond() {
        val stroke = TouchStroke(
            points = listOf(TouchPoint(10, 10, tMs = 100)),
            endTMs = 50,
        )

        val built = UnlockGestureCodec.build(listOf(stroke), screen).strokes.single()

        assertEquals(1L, built.durationMs)
    }

    @Test
    fun strokeCountIsBounded() {
        val strokes = List(70) { tapStroke(it * 20) }

        val pattern = UnlockGestureCodec.build(strokes, screen)

        assertEquals(64, pattern.strokes.size)
    }

    @Test
    fun manyPointsAreResampledToLimit() {
        val points = List(1_000) { index ->
            TouchPoint(x = index * 20, y = index * 20, tMs = index)
        }
        val stroke = TouchStroke(points, endTMs = 1_000)

        val built = UnlockGestureCodec.build(listOf(stroke), ScreenGeometry(100_000, 100_000, 0))
            .strokes.single()

        assertEquals(400, built.points.size)
        assertTrue(built.points.all { it.x in 0f..1f && it.y in 0f..1f })
    }

    @Test
    fun emptyStrokesProduceEmptyPattern() {
        val pattern = UnlockGestureCodec.build(emptyList(), screen)

        assertEquals(1, pattern.rotation)
        assertTrue(pattern.strokes.isEmpty())
    }
}
