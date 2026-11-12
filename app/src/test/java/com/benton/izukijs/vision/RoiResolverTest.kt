package com.benton.izukijs.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoiResolverTest {

    @Test
    fun emptyRoiIsFullImage() {
        val rois = RoiResolver.resolve(null, emptyMap(), 1080, 2400)
        assertEquals(listOf(Box(0, 0, 1080, 2400)), rois)
    }

    @Test
    fun negativeCoordinatesAreRelativeToRightAndBottom() {
        val rois = RoiResolver.resolve(
            RoiSpec(rect = RectSpec(x = -100, y = -200, width = 50, height = 40)),
            emptyMap(),
            1080,
            2400,
        )
        assertEquals(listOf(Box(980, 2200, 1030, 2240)), rois)
    }

    @Test
    fun zeroSizeExtendsToEdge() {
        val rois = RoiResolver.resolve(
            RoiSpec(rect = RectSpec(x = 100, y = 200, width = 0, height = 0)),
            emptyMap(),
            1080,
            2400,
        )
        assertEquals(listOf(Box(100, 200, 1080, 2400)), rois)
    }

    @Test
    fun negativeSizeTreatsPointAsBottomRight() {
        val rois = RoiResolver.resolve(
            RoiSpec(rect = RectSpec(x = 300, y = 400, width = -100, height = -200)),
            emptyMap(),
            1080,
            2400,
        )
        assertEquals(listOf(Box(200, 200, 300, 400)), rois)
    }

    @Test
    fun offsetIsAppliedToXYWH() {
        val rois = RoiResolver.resolve(
            RoiSpec(
                rect = RectSpec(x = 100, y = 200, width = 300, height = 400),
                offset = intArrayOf(10, 20, 30, 40),
            ),
            emptyMap(),
            1080,
            2400,
        )
        assertEquals(listOf(Box(110, 220, 440, 660)), rois)
    }

    @Test
    fun refUsesEachPreviousResultAsRoi() {
        val refs = mapOf(
            "icon" to listOf(
                VisionResult(Box(10, 20, 30, 40)),
                VisionResult(Box(100, 200, 150, 260)),
            ),
        )
        val rois = RoiResolver.resolve(RoiSpec(ref = "icon"), refs, 1080, 2400)
        assertEquals(listOf(Box(10, 20, 30, 40), Box(100, 200, 150, 260)), rois)
    }

    @Test
    fun refWithoutResultsIsEmpty() {
        assertTrue(RoiResolver.resolve(RoiSpec(ref = "missing"), emptyMap(), 1080, 2400).isEmpty())
    }

    @Test
    fun outOfRangeRoiIsClippedOrDropped() {
        val clipped = RoiResolver.resolve(
            RoiSpec(rect = RectSpec(x = -50, y = -50, width = 100, height = 100)),
            emptyMap(),
            1080,
            2400,
        )
        assertEquals(listOf(Box(1030, 2350, 1080, 2400)), clipped)

        val dropped = RoiResolver.resolve(
            RoiSpec(rect = RectSpec(x = 2000, y = 3000, width = 100, height = 100)),
            emptyMap(),
            1080,
            2400,
        )
        assertTrue(dropped.isEmpty())
    }
}
