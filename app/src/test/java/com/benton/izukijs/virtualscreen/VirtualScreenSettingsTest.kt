package com.benton.izukijs.virtualscreen

import com.benton.izukijs.model.RunMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualScreenSettingsTest {

    @Test
    fun resolutionClampsOutOfRangeValues() {
        val normalized = VirtualScreenResolution(width = 10, height = 99999, dpi = 1).normalized()
        assertEquals(VirtualScreenResolution.MIN_SIZE, normalized.width)
        assertEquals(VirtualScreenResolution.MAX_SIZE, normalized.height)
        assertEquals(VirtualScreenResolution.MIN_DPI, normalized.dpi)
        assertTrue(normalized.isValid)
    }

    @Test
    fun swappedExchangesWidthAndHeight() {
        val swapped = VirtualScreenResolution(1600, 900, 280).swapped()
        assertEquals(900, swapped.width)
        assertEquals(1600, swapped.height)
        assertEquals(280, swapped.dpi)
    }

    @Test
    fun swappedKeepsResolutionValid() {
        val swapped = VirtualScreenResolution(width = 10, height = 99999, dpi = 240).swapped()
        assertEquals(VirtualScreenResolution.MAX_SIZE, swapped.width)
        assertEquals(VirtualScreenResolution.MIN_SIZE, swapped.height)
        assertTrue(swapped.isValid)
    }

    @Test
    fun settingsAlwaysAllowForeground() {
        val normalized = VirtualScreenSettings(
            allowedModes = setOf(RunMode.BACKGROUND),
            defaultMode = RunMode.BACKGROUND,
        ).normalized()
        assertTrue(RunMode.FOREGROUND in normalized.allowedModes)
        assertEquals(RunMode.BACKGROUND, normalized.defaultMode)
    }

    @Test
    fun disallowedDefaultFallsBackToForeground() {
        val normalized = VirtualScreenSettings(
            allowedModes = setOf(RunMode.FOREGROUND),
            defaultMode = RunMode.BACKGROUND,
        ).normalized()
        assertEquals(RunMode.FOREGROUND, normalized.defaultMode)
    }

    @Test
    fun autoStopAfterRunDefaultsToTrueAndSurvivesNormalization() {
        assertTrue(VirtualScreenSettings().autoStopAfterRun)
        val disabled = VirtualScreenSettings(autoStopAfterRun = false).normalized()
        assertFalse(disabled.autoStopAfterRun)
    }

    @Test
    fun systemDecorationsDefaultsToOffAndSurvivesNormalization() {
        assertFalse(VirtualScreenSettings().showSystemDecorations)
        assertTrue(
            VirtualScreenSettings(showSystemDecorations = true).normalized().showSystemDecorations,
        )
    }
}
