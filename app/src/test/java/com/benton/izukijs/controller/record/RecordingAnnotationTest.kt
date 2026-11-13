package com.benton.izukijs.controller.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingAnnotationTest {

    @Test
    fun sameOrientation_scalesEachAxisIndependently() {
        val scale = annotationScale(
            frameWidth = 540,
            frameHeight = 1170,
            screenWidth = 1080,
            screenHeight = 2340,
        )
        assertEquals(0.5f, scale!!.x, 0.0001f)
        assertEquals(0.5f, scale.y, 0.0001f)
    }

    @Test
    fun landscapeFrame_usesDifferentAxisScale() {
        val scale = annotationScale(
            frameWidth = 1170,
            frameHeight = 540,
            screenWidth = 2340,
            screenHeight = 1080,
        )
        assertEquals(0.5f, scale!!.x, 0.0001f)
        assertEquals(0.5f, scale.y, 0.0001f)
    }

    @Test
    fun orientationMismatch_returnsNull() {
        assertNull(
            annotationScale(
                frameWidth = 2340,
                frameHeight = 1080,
                screenWidth = 1080,
                screenHeight = 2340,
            ),
        )
    }

    @Test
    fun unknownActionScreen_fallsBackToIdentity() {
        val scale = annotationScale(
            frameWidth = 1080,
            frameHeight = 2340,
            screenWidth = 0,
            screenHeight = 0,
        )
        assertEquals(1f, scale!!.x, 0.0001f)
        assertEquals(1f, scale.y, 0.0001f)
    }
}
