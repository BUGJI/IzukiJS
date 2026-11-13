package com.benton.izukijs.controller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAnimationProbeTest {

    private val samples = 24 * 32

    @Test
    fun smallChangesAreNotAnimation() {
        assertFalse(ScreenAnimationProbe.isAnimationChange(0, samples))
        assertFalse(ScreenAnimationProbe.isAnimationChange(1, samples))
        assertFalse(ScreenAnimationProbe.isAnimationChange(15, samples))
    }

    @Test
    fun largeChangesAreAnimation() {
        assertTrue(ScreenAnimationProbe.isAnimationChange(32, samples))
        assertTrue(ScreenAnimationProbe.isAnimationChange(samples, samples))
    }

    @Test
    fun tinySampleSetsUseMinimumThreshold() {
        assertFalse(ScreenAnimationProbe.isAnimationChange(15, 64))
        assertTrue(ScreenAnimationProbe.isAnimationChange(16, 64))
    }
}
