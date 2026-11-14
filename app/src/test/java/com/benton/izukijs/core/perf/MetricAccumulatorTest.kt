package com.benton.izukijs.core.perf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricAccumulatorTest {

    @Test
    fun emptyAccumulatorHasZeroStats() {
        val accumulator = MetricAccumulator()

        assertEquals(0L, accumulator.count())
        assertEquals(0L, accumulator.totalNanos())
        assertEquals(0L, accumulator.maxNanos())
        assertArrayEquals(LongArray(0), accumulator.snapshot())
    }

    @Test
    fun aggregatesCountTotalMaxAndSamples() {
        val accumulator = MetricAccumulator()

        accumulator.add(1L)
        accumulator.add(2L)
        accumulator.add(3L)

        assertEquals(3L, accumulator.count())
        assertEquals(6L, accumulator.totalNanos())
        assertEquals(3L, accumulator.maxNanos())
        assertEquals(setOf(1L, 2L, 3L), accumulator.snapshot().toSet())
    }

    @Test
    fun negativeSamplesAreClampedToZero() {
        val accumulator = MetricAccumulator()

        accumulator.add(-5L)

        assertEquals(1L, accumulator.count())
        assertEquals(0L, accumulator.totalNanos())
        assertEquals(0L, accumulator.maxNanos())
        assertArrayEquals(longArrayOf(0L), accumulator.snapshot())
    }

    @Test
    fun ringBufferKeepsOnlyCapacity() {
        val accumulator = MetricAccumulator(sampleCapacity = 2)

        accumulator.add(1L)
        accumulator.add(2L)
        accumulator.add(3L)

        assertEquals(3L, accumulator.count())
        assertEquals(6L, accumulator.totalNanos())
        assertEquals(setOf(2L, 3L), accumulator.snapshot().toSet())
    }

    @Test
    fun rejectsNonPositiveCapacity() {
        val error = runCatching { MetricAccumulator(sampleCapacity = 0) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun percentilePicksNearestRank() {
        val sorted = longArrayOf(1L, 2L, 3L, 4L)

        assertEquals(2L, PerfStats.percentile(sorted, 0.50))
        assertEquals(3L, PerfStats.percentile(sorted, 0.95))
        assertEquals(3L, PerfStats.percentile(sorted, 0.99))
        assertEquals(1L, PerfStats.percentile(sorted, 0.0))
        assertEquals(4L, PerfStats.percentile(sorted, 1.0))
    }

    @Test
    fun percentileHandlesEmptyAndSingleSample() {
        assertEquals(0L, PerfStats.percentile(LongArray(0), 0.5))
        assertEquals(7L, PerfStats.percentile(longArrayOf(7L), 0.5))
    }
}
