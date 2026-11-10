package com.benton.izukijs.runtime.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorRegionAccumulatorTest {

    private fun region(
        pixels: Int,
        x: Int = 0,
        y: Int = 0,
    ): ColorRegion = ColorRegion(
        x = x,
        y = y,
        w = 10,
        h = 10,
        pixels = pixels,
        cx = x + 5,
        cy = y + 5,
    )

    @Test
    fun filtersRegionsBelowMinPixels() {
        val accumulator = ColorRegionAccumulator(minPixels = 100, maxRegions = 10)

        accumulator.offer(region(pixels = 99))
        accumulator.offer(region(pixels = 100))
        accumulator.offer(region(pixels = 150))

        assertEquals(listOf(150, 100), accumulator.toList().map { it.pixels })
    }

    @Test
    fun keepsLargestRegionsWhenOverLimit() {
        val accumulator = ColorRegionAccumulator(minPixels = 1, maxRegions = 3)

        listOf(10, 50, 30, 40, 20).forEach { accumulator.offer(region(pixels = it)) }

        assertEquals(listOf(50, 40, 30), accumulator.toList().map { it.pixels })
    }

    @Test
    fun sortsEqualSizesByPosition() {
        val accumulator = ColorRegionAccumulator(minPixels = 1, maxRegions = 10)

        accumulator.offer(region(pixels = 100, x = 200, y = 100))
        accumulator.offer(region(pixels = 100, x = 100, y = 200))
        accumulator.offer(region(pixels = 100, x = 100, y = 100))

        assertEquals(listOf(100, 100, 200), accumulator.toList().map { it.y })
    }
}
