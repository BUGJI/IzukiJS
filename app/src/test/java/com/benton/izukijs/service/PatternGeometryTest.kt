package com.benton.izukijs.service

import com.benton.izukijs.model.PatternPoint
import com.benton.izukijs.model.UnlockPattern
import com.benton.izukijs.model.UnlockStroke
import org.junit.Assert.assertEquals
import org.junit.Test

class PatternGeometryTest {

    @Test
    fun mapsNormalizedStrokesToScreen() {
        val pattern = UnlockPattern(
            rotation = 0,
            strokes = listOf(
                UnlockStroke(
                    points = listOf(
                        PatternPoint(0.25f, 0.25f),
                        PatternPoint(0.5f, 0.5f),
                        PatternPoint(0.75f, 0.75f, timeMs = 32L),
                    ),
                ),
            ),
        )

        val strokes = patternToGestureStrokes(pattern, width = 1000, height = 2000)

        assertEquals(1, strokes.size)
        val points = strokes[0]
        assertEquals(3, points.size)
        assertEquals(250f, points[0].x, 0.01f)
        assertEquals(500f, points[0].y, 0.01f)
        assertEquals(500f, points[1].x, 0.01f)
        assertEquals(1000f, points[1].y, 0.01f)
        assertEquals(750f, points[2].x, 0.01f)
        assertEquals(1500f, points[2].y, 0.01f)
        assertEquals(0L, points[0].timeMs)
        assertEquals(16L, points[1].timeMs)
        assertEquals(32L, points[2].timeMs)
    }

    @Test
    fun keepsMultipleStrokesSeparate() {
        val pattern = UnlockPattern(
            rotation = 1,
            strokes = listOf(
                UnlockStroke(points = listOf(PatternPoint(0.5f, 0.5f))),
                UnlockStroke(points = listOf(PatternPoint(1f, 1f))),
            ),
        )

        val strokes = patternToGestureStrokes(pattern, width = 2000, height = 1000)

        assertEquals(2, strokes.size)
        assertEquals(1000f, strokes[0][0].x, 0.01f)
        assertEquals(500f, strokes[0][0].y, 0.01f)
        assertEquals(2000f, strokes[1][0].x, 0.01f)
        assertEquals(1000f, strokes[1][0].y, 0.01f)
    }
}
