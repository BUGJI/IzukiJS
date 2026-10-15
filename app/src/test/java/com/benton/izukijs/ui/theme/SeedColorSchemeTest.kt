package com.benton.izukijs.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SeedColorSchemeTest {

    @Test
    fun seedColorScheme_isDeterministic() {
        val a = seedColorScheme(0xFF1B5E9E.toInt(), dark = false)
        val b = seedColorScheme(0xFF1B5E9E.toInt(), dark = false)
        assertEquals(a.primary, b.primary)
        assertEquals(a.surfaceContainer, b.surfaceContainer)
    }

    @Test
    fun seedColorScheme_darkAndLightDiffer() {
        val seed = 0xFF00696D.toInt()
        val light = seedColorScheme(seed, dark = false)
        val dark = seedColorScheme(seed, dark = true)
        assertNotEquals(light.background, dark.background)
    }

    @Test
    fun withAmoled_forcesPureBlackSurfaces() {
        val scheme = seedColorScheme(0xFF2E7D32.toInt(), dark = true).withAmoled()
        assertEquals(AmoledBlack, scheme.background)
        assertEquals(AmoledBlack, scheme.surface)
        assertEquals(AmoledBlack, scheme.surfaceDim)
        assertEquals(AmoledBlack, scheme.surfaceContainerLowest)
        assertEquals(AmoledSurface, scheme.surfaceContainer)
        assertEquals(Color(0xFF222222), scheme.surfaceContainerHighest)
    }
}
