package com.benton.izukijs.runtime

import com.benton.izukijs.model.RunMode
import com.benton.izukijs.virtualscreen.VirtualScreenSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunModeResolverTest {

    private val backgroundAllowed = VirtualScreenSettings(
        allowedModes = setOf(RunMode.FOREGROUND, RunMode.BACKGROUND),
        defaultMode = RunMode.FOREGROUND,
    )

    @Test
    fun keepsExplicitForeground() {
        val result = RunModeResolver.resolve(
            requested = RunMode.FOREGROUND,
            settings = backgroundAllowed,
            virtualScreenAvailable = true,
        )
        assertEquals(RunMode.FOREGROUND, result)
    }

    @Test
    fun keepsBackgroundWhenAllowedAndAvailable() {
        val result = RunModeResolver.resolve(
            requested = RunMode.BACKGROUND,
            settings = backgroundAllowed,
            virtualScreenAvailable = true,
        )
        assertEquals(RunMode.BACKGROUND, result)
    }

    @Test
    fun fallsBackWhenBackgroundIsNotAllowed() {
        var fallback = false
        val result = RunModeResolver.resolve(
            requested = RunMode.BACKGROUND,
            settings = VirtualScreenSettings(allowedModes = setOf(RunMode.FOREGROUND)),
            virtualScreenAvailable = true,
            onFallback = { fallback = true },
        )
        assertEquals(RunMode.FOREGROUND, result)
        assertTrue(fallback)
    }

    @Test
    fun fallsBackWhenVirtualScreenIsUnavailable() {
        var fallback = false
        val result = RunModeResolver.resolve(
            requested = RunMode.BACKGROUND,
            settings = backgroundAllowed,
            virtualScreenAvailable = false,
            onFallback = { fallback = true },
        )
        assertEquals(RunMode.FOREGROUND, result)
        assertTrue(fallback)
    }

    @Test
    fun usesDefaultModeWhenNotRequested() {
        val result = RunModeResolver.resolve(
            requested = null,
            settings = backgroundAllowed.copy(defaultMode = RunMode.BACKGROUND),
            virtualScreenAvailable = true,
        )
        assertEquals(RunMode.BACKGROUND, result)
    }

    @Test
    fun defaultBackgroundAlsoFallsBackWhenUnavailable() {
        var fallback = false
        val result = RunModeResolver.resolve(
            requested = null,
            settings = backgroundAllowed.copy(defaultMode = RunMode.BACKGROUND),
            virtualScreenAvailable = false,
            onFallback = { fallback = true },
        )
        assertEquals(RunMode.FOREGROUND, result)
        assertTrue(fallback)
    }

    @Test
    fun defaultForegroundDoesNotFallback() {
        var fallback = false
        val result = RunModeResolver.resolve(
            requested = null,
            settings = backgroundAllowed,
            virtualScreenAvailable = false,
            onFallback = { fallback = true },
        )
        assertEquals(RunMode.FOREGROUND, result)
        assertFalse(fallback)
    }
}
