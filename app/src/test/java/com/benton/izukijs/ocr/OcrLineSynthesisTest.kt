package com.benton.izukijs.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLineSynthesisTest {

    private fun block(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        confidence: Double? = null,
    ) = OcrBlock(text, left, top, right, bottom, confidence)

    @Test
    fun joinsBlocksOnSameRow() {
        val blocks = listOf(
            block("Read", 100, 100, 200, 130),
            block("timed", 210, 101, 300, 131),
            block("out", 310, 99, 380, 129),
        )
        val lines = OcrLineSynthesis.synthesize(blocks)
        assertEquals(1, lines.size)
        val line = lines.first()
        assertEquals(100, line.left)
        assertEquals(380, line.right)
        assertEquals(3, line.blockCount)
        assertTrue(line.text.contains("Read"))
    }

    @Test
    fun doesNotJoinDifferentRows() {
        val blocks = listOf(
            block("first", 100, 100, 200, 130),
            block("second", 100, 200, 220, 230),
        )
        val lines = OcrLineSynthesis.synthesize(blocks)
        assertEquals(2, lines.size)
    }

    @Test
    fun splitsFarApartColumns() {
        val blocks = listOf(
            block("left", 100, 100, 200, 130),
            block("right", 900, 100, 1000, 130),
        )
        val lines = OcrLineSynthesis.synthesize(blocks)
        assertEquals(2, lines.size)
    }

    @Test
    fun maxGapYCanMergeBlocksWithSlightJitter() {
        val blocks = listOf(
            block("微信团队", 100, 100, 300, 128),
            block("搜索", 310, 116, 400, 144),
        )
        assertEquals(2, OcrLineSynthesis.synthesize(blocks).size)
        val merged = OcrLineSynthesis.synthesize(
            blocks,
            OcrLineSynthesis.Options(maxGapY = 18),
        )
        assertEquals(1, merged.size)
        assertEquals(2, merged.first().blockCount)
    }

    @Test
    fun maxGapXControlsSameRowSplitting() {
        val blocks = listOf(
            block("left", 100, 100, 200, 130),
            block("right", 400, 100, 500, 130),
        )
        assertEquals(2, OcrLineSynthesis.synthesize(blocks).size)
        val merged = OcrLineSynthesis.synthesize(
            blocks,
            OcrLineSynthesis.Options(maxGapX = 250),
        )
        assertEquals(1, merged.size)
        assertEquals("left right", merged.first().text)
    }

    @Test
    fun readingOrderIsTopThenLeft() {
        val blocks = listOf(
            block("B", 500, 200, 600, 230),
            block("A", 100, 100, 200, 130),
            block("C", 100, 300, 200, 330),
        )
        val lines = OcrLineSynthesis.synthesize(blocks)
        assertEquals(listOf("A", "B", "C"), lines.map { it.text })
    }

    @Test
    fun matchesAcrossJoinedBlocksIgnoringWhitespace() {
        val lines = listOf(OcrLine("Read timed out", 100, 100, 500, 140))
        val matches = OcrMatcher.match(lines, "Read timed out")
        assertEquals(1, matches.size)
        assertFalse(matches.first().fuzzy)
    }

    @Test
    fun fuzzyMatchesOcrTypo() {
        val lines = listOf(OcrLine("并发三个请求,只有", 100, 100, 500, 140))
        val matches = OcrMatcher.match(lines, "并发三个靖求")
        assertEquals(1, matches.size)
        assertTrue(matches.first().fuzzy)
        assertTrue(matches.first().similarity > 0.5)
    }

    @Test
    fun fuzzyCanBeDisabled() {
        val lines = listOf(OcrLine("请求", 100, 100, 200, 140))
        assertTrue(OcrMatcher.match(lines, "靖求").isNotEmpty())
        assertTrue(OcrMatcher.match(lines, "靖求", fuzzy = false).isEmpty())
    }

    @Test
    fun lowConfidenceBlocksCanBeIgnored() {
        val lines = listOf(OcrLine("验证码 1234", 100, 100, 400, 140, confidence = 0.3))
        assertTrue(OcrMatcher.match(lines, "验证码", minConfidence = 0.5).isEmpty())
        assertEquals(1, OcrMatcher.match(lines, "验证码", minConfidence = 0.2).size)
    }
}
