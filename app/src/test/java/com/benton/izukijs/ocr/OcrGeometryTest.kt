package com.benton.izukijs.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrGeometryTest {

    @Test
    fun blockExposesCenterAndSizeFromBounds() {
        val block = OcrBlock("text", left = 100, top = 200, right = 300, bottom = 240)
        assertEquals(200, block.x)
        assertEquals(220, block.y)
        assertEquals(200, block.width)
        assertEquals(40, block.height)
        assertFalse(block.suspicious)
    }

    @Test
    fun blocksInsideImageAreUnchanged() {
        val result = OcrGeometry.sanitize(
            listOf(OcrBlock("ok", 10, 20, 100, 60)),
            imageWidth = 1200,
            imageHeight = 2670,
        )
        assertEquals(1, result.blocks.size)
        assertEquals(0, result.clamped)
        assertEquals(0, result.dropped)
        assertFalse(result.blocks.first().suspicious)
    }

    @Test
    fun outOfBoundsBlocksAreClampedAndMarked() {
        val result = OcrGeometry.sanitize(
            listOf(OcrBlock("wide", -50, 10, 1300, 60)),
            imageWidth = 1200,
            imageHeight = 2670,
        )
        assertEquals(1, result.blocks.size)
        assertEquals(1, result.clamped)
        val block = result.blocks.first()
        assertEquals(0, block.left)
        assertEquals(1200, block.right)
        assertTrue(block.suspicious)
    }

    @Test
    fun fullyOutsideOrDegenerateBlocksAreDropped() {
        val result = OcrGeometry.sanitize(
            listOf(
                OcrBlock("outside", 1300, 10, 1500, 60),
                OcrBlock("empty", 10, 10, 10, 60),
            ),
            imageWidth = 1200,
            imageHeight = 2670,
        )
        assertTrue(result.blocks.isEmpty())
        assertEquals(2, result.dropped)
    }
}
