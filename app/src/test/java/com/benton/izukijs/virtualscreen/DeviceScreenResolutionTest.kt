package com.benton.izukijs.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceScreenResolutionTest {

    @Test
    fun portraitDeviceScalesLongSideWithDpi() {
        val fitted = DeviceScreenResolution.fit(width = 1080, height = 2400, dpi = 420)
        assertEquals(VirtualScreenResolution(864, 1920, 336), fitted)
        assertTrue(fitted.isValid)
    }

    @Test
    fun landscapeDeviceScalesLongSideWithDpi() {
        val fitted = DeviceScreenResolution.fit(width = 2400, height = 1080, dpi = 420)
        assertEquals(VirtualScreenResolution(1920, 864, 336), fitted)
        assertTrue(fitted.isValid)
    }

    @Test
    fun smallDevicesAreNotUpscaled() {
        val fitted = DeviceScreenResolution.fit(width = 1280, height = 720, dpi = 240)
        assertEquals(VirtualScreenResolution.P720, fitted)
    }

    @Test
    fun scaledResolutionKeepsLogicalSize() {
        val fitted = DeviceScreenResolution.fit(width = 1080, height = 2400, dpi = 420)
        assertEquals(1080.0 / 420.0, fitted.width.toDouble() / fitted.dpi, 0.01)
        assertEquals(2400.0 / 420.0, fitted.height.toDouble() / fitted.dpi, 0.01)
    }

    @Test
    fun invalidMetricsFallBackToP720() {
        assertEquals(VirtualScreenResolution.P720, DeviceScreenResolution.fit(0, 2400, 420))
        assertEquals(VirtualScreenResolution.P720, DeviceScreenResolution.fit(1080, 0, 420))
        assertEquals(VirtualScreenResolution.P720, DeviceScreenResolution.fit(1080, 2400, 0))
    }
}
