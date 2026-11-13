package com.benton.izukijs.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FrameSampleHashTest {

    @Test
    fun sameSamplesProduceSameHash() {
        val samples = intArrayOf(1, 2, 3, 4)
        assertEquals(
            FrameSampleHash.hash(samples, 720, 1280),
            FrameSampleHash.hash(samples.copyOf(), 720, 1280),
        )
    }

    @Test
    fun changedSampleChangesHash() {
        val first = intArrayOf(1, 2, 3, 4)
        val second = intArrayOf(1, 2, 3, 5)
        assertNotEquals(
            FrameSampleHash.hash(first, 720, 1280),
            FrameSampleHash.hash(second, 720, 1280),
        )
    }

    @Test
    fun dimensionsParticipateInHash() {
        val samples = intArrayOf(1, 2, 3, 4)
        assertNotEquals(
            FrameSampleHash.hash(samples, 720, 1280),
            FrameSampleHash.hash(samples, 720, 1281),
        )
    }
}
