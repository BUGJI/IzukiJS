package com.benton.izukijs.ui.pip

import org.junit.Assert.assertEquals
import org.junit.Test

class PipControllerTest {

    @Test
    fun keepsLegalAspectRatio() {
        val ratio = PipController.clampAspectRatio(1280, 720)
        assertEquals(16f / 9f, ratio.numerator.toFloat() / ratio.denominator, 0.001f)
    }

    @Test
    fun clampsTooWideAndTooTall() {
        assertEquals(PipAspectRatio(239, 100), PipController.clampAspectRatio(5000, 100))
        assertEquals(PipAspectRatio(100, 239), PipController.clampAspectRatio(100, 5000))
    }

    @Test
    fun invalidSizeFallsBackTo16By9() {
        assertEquals(PipController.FALLBACK_RATIO, PipController.clampAspectRatio(0, 0))
    }
}
