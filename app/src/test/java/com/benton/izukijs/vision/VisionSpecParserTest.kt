package com.benton.izukijs.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionSpecParserTest {

    @Test
    fun parsesTemplateMatchWithDefaults() {
        val spec = VisionSpecParser.parse(
            """{"type":"TemplateMatch","template":"/sdcard/ok.png"}""",
        ) as TemplateMatchSpec

        assertEquals(listOf("/sdcard/ok.png"), spec.templates)
        assertEquals(listOf(0.8), spec.thresholds)
        assertEquals(5, spec.method)
        assertFalse(spec.greenMask)
        assertEquals(ResultOrderBy.Horizontal, spec.orderBy)
        assertEquals(0, spec.index)
    }

    @Test
    fun parsesTemplateMatchOptions() {
        val spec = VisionSpecParser.parse(
            """
            {
              "recognition": "TemplateMatch",
              "template": ["a.png", "b.png"],
              "threshold": [0.9, 0.8],
              "method": 10001,
              "greenMask": true,
              "roi": {"x": 10, "y": 20, "width": 300, "height": 400},
              "roi_offset": [1, 2, 3, 4],
              "orderBy": "Score",
              "index": -1
            }
            """.trimIndent(),
        ) as TemplateMatchSpec

        assertEquals(listOf("a.png", "b.png"), spec.templates)
        assertEquals(listOf(0.9, 0.8), spec.thresholds)
        assertEquals(10001, spec.method)
        assertTrue(spec.greenMask)
        assertEquals(RectSpec(10, 20, 300, 400), spec.roi?.rect)
        assertEquals(1, spec.roi?.offset?.get(0))
        assertEquals(ResultOrderBy.Score, spec.orderBy)
        assertEquals(-1, spec.index)
    }

    @Test
    fun parsesColorRanges() {
        val single = VisionSpecParser.parse(
            """{"type":"ColorMatch","lower":[0,120,120],"upper":[10,255,255],"method":40}""",
        ) as ColorMatchSpec
        assertEquals(1, single.ranges.size)
        assertEquals(40, single.method)

        val multiple = VisionSpecParser.parse(
            """{"type":"ColorMatch","lower":[[0,0,0],[10,10,10]],"upper":[[5,5,5],[20,20,20]],"connected":true,"count":10}""",
        ) as ColorMatchSpec
        assertEquals(2, multiple.ranges.size)
        assertTrue(multiple.connected)
        assertEquals(10, multiple.count)
    }

    @Test
    fun parsesOcrOptions() {
        val spec = VisionSpecParser.parse(
            """
            {
              "type": "OCR",
              "expected": ["确认", "OK"],
              "threshold": 0.5,
              "replace": [["0", "O"]],
              "colorFilter": {"method": 6, "lower": [200], "upper": [255]},
              "orderBy": "Length"
            }
            """.trimIndent(),
        ) as OcrSpec

        assertEquals(listOf("确认", "OK"), spec.expected)
        assertEquals(0.5, spec.threshold, 0.0)
        assertEquals(listOf("0" to "O"), spec.replace)
        assertEquals(6, spec.colorFilter?.method)
        assertEquals(ResultOrderBy.Length, spec.orderBy)
    }

    @Test
    fun parsesAndWithSubNames() {
        val spec = VisionSpecParser.parse(
            """
            {
              "type": "And",
              "allOf": [
                {"subName": "icon", "type": "TemplateMatch", "template": "icon.png"},
                {"type": "OCR", "roi": "icon", "expected": "确认"}
              ],
              "boxIndex": 1
            }
            """.trimIndent(),
        ) as AndSpec

        assertEquals(2, spec.allOf.size)
        assertEquals("icon", spec.allOf[0].subName)
        assertEquals("icon", spec.allOf[1].spec.roi?.ref)
        assertEquals(1, spec.boxIndex)
    }

    @Test
    fun parsesNeuralExpectedIndices() {
        val spec = VisionSpecParser.parse(
            """{"type":"NeuralNetworkDetect","model":"yolo.onnx","labels":["cat","dog"],"expected":["dog",1],"threshold":[0.5,0.4]}""",
        ) as NeuralDetectSpec

        assertEquals(listOf(1, 1), spec.expectedIndices)
        assertEquals(listOf(0.5, 0.4), spec.thresholds)
    }

    @Test
    fun missingTemplateThrows() {
        try {
            VisionSpecParser.parse("""{"type":"TemplateMatch"}""")
            throw AssertionError("expected VisionSpecException")
        } catch (expected: VisionSpecException) {
            assertTrue(expected.message!!.contains("template"))
        }
    }

    @Test
    fun unknownTypeThrows() {
        try {
            VisionSpecParser.parse("""{"type":"Nope"}""")
            throw AssertionError("expected VisionSpecException")
        } catch (expected: VisionSpecException) {
            assertTrue(expected.message!!.contains("Nope"))
        }
    }

    @Test
    fun noRoiMeansNull() {
        val spec = VisionSpecParser.parse("""{"type":"TemplateMatch","template":"a.png"}""")
        assertNull(spec.roi)
    }
}
