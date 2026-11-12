package com.benton.izukijs.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralPostprocessTest {

    private val roi = Box(0, 0, 640, 640)

    private fun data(): FloatArray {
        // [1, 6, 2]：cx, cy, w, h, cls0, cls1
        val candidates = arrayOf(
            floatArrayOf(50f, 60f, 20f, 10f, 0.9f, 0.1f),
            floatArrayOf(200f, 300f, 30f, 20f, 0.2f, 0.8f),
        )
        val out = FloatArray(6 * 2)
        for (channel in 0 until 6) {
            for (index in 0 until 2) {
                out[channel * 2 + index] = candidates[index][channel]
            }
        }
        return out
    }

    @Test
    fun parsesChannelsFirstLayout() {
        val results = parseYoloDetections(
            shape = longArrayOf(1, 6, 2),
            data = data(),
            roi = roi,
            scale = 1.0,
            padLeft = 0,
            padTop = 0,
            thresholds = listOf(0.5),
            expectedIndices = emptyList(),
            labels = listOf("cat", "dog"),
        )
        assertEquals(2, results.size)
        assertEquals(listOf(0, 1), results.map { it.clsIndex })
        assertEquals(listOf("cat", "dog"), results.map { it.label })
    }

    @Test
    fun parsesTransposedLayout() {
        val transposed = FloatArray(6 * 2)
        val original = data()
        for (channel in 0 until 6) {
            for (index in 0 until 2) {
                transposed[index * 6 + channel] = original[channel * 2 + index]
            }
        }
        val results = parseYoloDetections(
            shape = longArrayOf(1, 2, 6),
            data = transposed,
            roi = roi,
            scale = 1.0,
            padLeft = 0,
            padTop = 0,
            thresholds = listOf(0.5),
            expectedIndices = emptyList(),
            labels = emptyList(),
        )
        assertEquals(2, results.size)
    }

    @Test
    fun filtersByExpectedClass() {
        val results = parseYoloDetections(
            shape = longArrayOf(1, 6, 2),
            data = data(),
            roi = roi,
            scale = 1.0,
            padLeft = 0,
            padTop = 0,
            thresholds = listOf(0.5, 0.5),
            expectedIndices = listOf(1),
            labels = listOf("cat", "dog"),
        )
        assertEquals(1, results.size)
        assertEquals(1, results.first().clsIndex)
    }

    @Test
    fun mapsThresholdsByExpectedClassOrder() {
        val results = parseYoloDetections(
            shape = longArrayOf(1, 6, 2),
            data = data(),
            roi = roi,
            scale = 1.0,
            padLeft = 0,
            padTop = 0,
            // expected 顺序为 [dog, cat]：dog 阈值 0.5，cat 阈值 0.95。
            thresholds = listOf(0.5, 0.95),
            expectedIndices = listOf(1, 0),
            labels = listOf("cat", "dog"),
        )
        assertEquals(1, results.size)
        assertEquals(1, results.first().clsIndex)
    }

    @Test
    fun unletterboxesAndClipsToRoi() {
        val results = parseYoloDetections(
            shape = longArrayOf(1, 5, 1),
            data = floatArrayOf(10f, 10f, 100f, 100f, 0.9f),
            roi = Box(100, 100, 200, 200),
            scale = 0.5,
            padLeft = 10,
            padTop = 20,
            thresholds = listOf(0.5),
            expectedIndices = emptyList(),
            labels = emptyList(),
        )
        assertEquals(1, results.size)
        assertTrue(roi.contains(results.first().box.centerX, results.first().box.centerY))
    }

    @Test
    fun ignoresMalformedShape() {
        assertTrue(
            parseYoloDetections(
                shape = longArrayOf(1, 6),
                data = data(),
                roi = roi,
                scale = 1.0,
                padLeft = 0,
                padTop = 0,
                thresholds = listOf(0.5),
                expectedIndices = emptyList(),
                labels = emptyList(),
            ).isEmpty(),
        )
    }
}
