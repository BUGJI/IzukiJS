package com.benton.izukijs.privileged.internal

import com.benton.izukijs.model.TouchInputDevice
import org.junit.Assert.assertEquals
import org.junit.Test

class TouchCoordMapperTest {

    private val device = TouchInputDevice(
        path = "/dev/input/event1",
        maxX = 999,
        maxY = 999,
        minX = 0,
        minY = 0,
    )

    @Test
    fun rotation0_keepsNaturalCoordinates() {
        val mapped = TouchCoordMapper.mapPoint(250, 500, device, ScreenGeometry(1000, 2000, 0))
        assertEquals(250, mapped.first)
        assertEquals(1000, mapped.second)
    }

    @Test
    fun rotation90_rotatesIntoLandscapeLogicalSpace() {
        val mapped = TouchCoordMapper.mapPoint(250, 500, device, ScreenGeometry(2000, 1000, 1))
        assertEquals(1000, mapped.first)
        assertEquals(749, mapped.second)
    }

    @Test
    fun rotation180_mirrorsBothAxes() {
        val mapped = TouchCoordMapper.mapPoint(250, 500, device, ScreenGeometry(1000, 2000, 2))
        assertEquals(749, mapped.first)
        assertEquals(999, mapped.second)
    }

    @Test
    fun rotation270_rotatesIntoLandscapeLogicalSpace() {
        val mapped = TouchCoordMapper.mapPoint(250, 500, device, ScreenGeometry(2000, 1000, 3))
        assertEquals(999, mapped.first)
        assertEquals(250, mapped.second)
    }

    @Test
    fun allPointsStayInsideLogicalBounds() {
        val geometries = listOf(
            ScreenGeometry(1000, 2000, 0),
            ScreenGeometry(2000, 1000, 1),
            ScreenGeometry(1000, 2000, 2),
            ScreenGeometry(2000, 1000, 3),
        )
        for (geometry in geometries) {
            val mapped = TouchCoordMapper.mapPoint(999, 999, device, geometry)
            assertEquals(true, mapped.first in 0 until geometry.width)
            assertEquals(true, mapped.second in 0 until geometry.height)
        }
    }
}
