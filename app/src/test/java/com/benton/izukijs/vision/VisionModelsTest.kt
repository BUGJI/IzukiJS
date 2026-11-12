package com.benton.izukijs.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionModelsTest {

    private fun result(
        left: Int,
        top: Int,
        width: Int = 10,
        height: Int = 10,
        score: Double = 0.9,
        count: Int = 10,
        text: String? = null,
    ): VisionResult = VisionResult(
        box = Box.of(left, top, width, height),
        score = score,
        count = count,
        text = text,
    )

    @Test
    fun pythonicIndexMatchesPython() {
        assertEquals(0, pythonicIndex(3, 0))
        assertEquals(2, pythonicIndex(3, 2))
        assertEquals(2, pythonicIndex(3, -1))
        assertEquals(0, pythonicIndex(3, -3))
        assertNull(pythonicIndex(3, 3))
        assertNull(pythonicIndex(3, -4))
    }

    @Test
    fun horizontalOrderSortsByXThenY() {
        val ordered = orderResults(
            listOf(result(200, 10), result(100, 50), result(100, 10)),
            ResultOrderBy.Horizontal,
        )
        assertEquals(listOf(100 to 10, 100 to 50, 200 to 10), ordered.map { it.box.left to it.box.top })
    }

    @Test
    fun scoreOrderIsDescending() {
        val ordered = orderResults(
            listOf(result(0, 0, score = 0.5), result(0, 0, score = 0.9), result(0, 0, score = 0.7)),
            ResultOrderBy.Score,
        )
        assertEquals(listOf(0.9, 0.7, 0.5), ordered.map { it.score })
    }

    @Test
    fun expectedOrderUsesRegexOrder() {
        val ordered = orderResults(
            listOf(
                result(0, 0, text = "cancel"),
                result(0, 0, text = "confirm"),
                result(0, 0, text = "other"),
            ),
            ResultOrderBy.Expected,
            expectedText = listOf(Regex("confirm"), Regex("cancel")),
        )
        assertEquals(listOf("confirm", "cancel", "other"), ordered.map { it.text })
    }

    @Test
    fun nmsSuppressesOverlappingLowerScore() {
        val kept = nmsByScore(
            listOf(
                result(0, 0, score = 0.9),
                result(1, 1, score = 0.8),
                result(100, 100, score = 0.7),
            ),
            threshold = 0.2,
        )
        assertEquals(2, kept.size)
        assertTrue(kept.any { it.box.left == 0 })
        assertTrue(kept.any { it.box.left == 100 })
    }

    @Test
    fun nmsKeepsLowScoresWhenThresholdIsLower() {
        val kept = nmsByScore(
            listOf(
                result(0, 0, score = 0.05),
                result(100, 100, score = 0.06),
            ),
            threshold = 0.2,
            minScore = 0.05,
        )
        assertEquals(2, kept.size)
    }

    @Test
    fun nmsByCountKeepsLargestRegions() {
        val kept = nmsByCount(
            listOf(
                result(0, 0, count = 100),
                result(1, 1, count = 80),
                result(100, 100, count = 50),
            ),
        )
        assertEquals(listOf(100, 50), kept.map { it.count })
    }

    @Test
    fun boxIntersectionAndContains() {
        val box = Box.of(10, 20, 30, 40)
        assertEquals(25, box.centerX)
        assertEquals(40, box.centerY)
        assertTrue(box.contains(10, 20))
        assertTrue(!box.contains(40, 60))
        assertEquals(Box.of(20, 30, 20, 30), box.intersect(Box.of(20, 30, 100, 100)))
    }
}
