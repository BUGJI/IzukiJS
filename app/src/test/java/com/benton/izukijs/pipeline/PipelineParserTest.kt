package com.benton.izukijs.pipeline

import com.benton.izukijs.vision.RectSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PipelineParserTest {

    @Test
    fun parsesV1Node() {
        val spec = PipelineParser.parse(
            """
            {
              "A": {
                "recognition": "TemplateMatch",
                "template": "a.png",
                "threshold": 0.9,
                "method": 5,
                "roi": [10, 20, 30, 40],
                "roi_offset": [1, 2, 3, 4],
                "action": "Click",
                "target": [100, 200],
                "next": ["B", "[JumpBack]C", {"name": "D", "anchor": true}],
                "on_error": "E",
                "anchor": "X",
                "rate_limit": 500,
                "timeout": 3000,
                "pre_delay": 100,
                "post_delay": 200,
                "pre_wait_freezes": 300,
                "post_wait_freezes": {"time": 150, "threshold": 0.9, "method": 1},
                "repeat": 2,
                "repeat_delay": 50,
                "max_hit": 5,
                "focus": {"start": "ui"},
                "attach": {"tag": "demo"}
              }
            }
            """.trimIndent(),
        )

        val node = spec["A"]!!
        assertEquals(RecognitionType.TemplateMatch, node.recognition.type)
        assertEquals("a.png", node.recognition.param.optString("template"))
        assertEquals(0.9, node.recognition.param.optDouble("threshold"), 1e-9)
        assertEquals(5, node.recognition.param.optInt("method"))
        assertTrue(node.recognition.param.optJSONArray("roi") != null)
        assertTrue(node.recognition.param.optJSONArray("roi_offset") != null)

        assertEquals(ActionType.Click, node.action.type)
        assertEquals(2, node.action.param.getJSONArray("target").length())

        assertEquals(3, node.next.size)
        assertEquals(NodeAttr("B"), node.next[0])
        assertTrue(node.next[1].jumpBack)
        assertEquals("C", node.next[1].name)
        assertTrue(node.next[2].anchorRef)
        assertEquals("D", node.next[2].name)
        assertEquals(listOf(NodeAttr("E")), node.onError)
        assertEquals(mapOf("X" to "A"), node.anchors)

        assertEquals(500L, node.rateLimitMs)
        assertEquals(3000L, node.timeoutMs)
        assertEquals(100L, node.preDelayMs)
        assertEquals(200L, node.postDelayMs)
        assertEquals(300L, node.preWaitFreezes.timeMs)
        assertEquals(150L, node.postWaitFreezes.timeMs)
        assertEquals(0.9, node.postWaitFreezes.threshold, 1e-9)
        assertEquals(1, node.postWaitFreezes.method)
        assertEquals(2, node.repeat)
        assertEquals(50L, node.repeatDelayMs)
        assertEquals(5, node.maxHit)
        assertEquals("ui", node.focus?.optString("start"))
        assertEquals("demo", node.attach.optString("tag"))
    }

    @Test
    fun parsesV2Node() {
        val spec = PipelineParser.parse(
            """
            {
              "A": {
                "recognition": {
                  "type": "OCR",
                  "param": {"expected": ["OK"], "threshold": 0.4}
                },
                "action": {
                  "type": "InputText",
                  "param": {"input_text": "hello"}
                },
                "next": ["B"]
              },
              "B": {}
            }
            """.trimIndent(),
        )

        val node = spec["A"]!!
        assertEquals(RecognitionType.OCR, node.recognition.type)
        assertEquals("OK", node.recognition.param.getJSONArray("expected").optString(0))
        assertEquals(0.4, node.recognition.param.optDouble("threshold"), 1e-9)
        assertEquals(ActionType.InputText, node.action.type)
        assertEquals("hello", node.action.param.optString("input_text"))
    }

    @Test
    fun parsesAllAnchorForms() {
        val spec = PipelineParser.parse(
            """
            {
              "A": {"anchor": "X"},
              "B": {"anchor": ["X", "Y"]},
              "C": {"anchor": {"X": "A", "Y": ""}}
            }
            """.trimIndent(),
        )

        assertEquals(mapOf("X" to "A"), spec["A"]!!.anchors)
        assertEquals(mapOf("X" to "B", "Y" to "B"), spec["B"]!!.anchors)
        assertEquals(mapOf("X" to "A", "Y" to ""), spec["C"]!!.anchors)
    }

    @Test
    fun parsesTargetKinds() {
        fun target(raw: Any?): TargetSpec =
            PipelineParser.parseTarget(JSONObject().put("value", raw), "value", "value_offset", TargetSpec())

        assertEquals(TargetType.Self, target(true).type)
        assertEquals(TargetType.PreTask, target("NodeA").type)
        assertEquals("NodeA", target("NodeA").ref)
        assertEquals(TargetType.Anchor, target("[Anchor]X").type)
        assertEquals("X", target("[Anchor]X").ref)

        val region = target(org.json.JSONArray().put(1).put(2).put(3).put(4))
        assertEquals(TargetType.Region, region.type)
        assertEquals(1, region.rect?.x)
        assertEquals(2, region.rect?.y)
        assertEquals(3, region.rect?.width)
        assertEquals(4, region.rect?.height)

        val point = target(org.json.JSONArray().put(7).put(8))
        assertEquals(1, point.rect?.width)
        assertEquals(1, point.rect?.height)

        val offset = PipelineParser.parseTarget(
            JSONObject().put("value", true).put("value_offset", org.json.JSONArray().put(5).put(6).put(7).put(8)),
            "value",
            "value_offset",
            TargetSpec(),
        )
        assertEquals(5, offset.offset[0])
        assertEquals(8, offset.offset[3])
    }

    @Test
    fun parsesWaitFreezesObject() {
        val wait = PipelineParser.parseWaitFreezes(
            JSONObject()
                .put("time", 500)
                .put("target", "[Anchor]X")
                .put("target_offset", org.json.JSONArray().put(1).put(2).put(3).put(4))
                .put("threshold", 0.8)
                .put("method", 3)
                .put("rate_limit", 100)
                .put("timeout", -1),
        )

        assertEquals(500L, wait.timeMs)
        assertEquals(TargetType.Anchor, wait.target.type)
        assertEquals("X", wait.target.ref)
        assertEquals(4, wait.target.offset[3])
        assertEquals(0.8, wait.threshold, 1e-9)
        assertEquals(3, wait.method)
        assertEquals(100L, wait.rateLimitMs)
        assertEquals(-1L, wait.timeoutMs)
    }

    @Test
    fun parseDocumentTreatsDefaultSectionsAsDefaults() {
        val spec = PipelineParser.parseDocument(
            JSONObject(
                """
                {
                  "Default": {
                    "rate_limit": 2000,
                    "pre_delay": 50,
                    "recognition": "OCR",
                    "threshold": 0.4
                  },
                  "TemplateMatch": {"recognition": "TemplateMatch", "threshold": 0.6},
                  "Click": {"action": "Click", "target": true},
                  "LoginNode": {"expected": ["x"]},
                  "TemplateNode": {"recognition": "TemplateMatch", "template": "t.png"},
                  "ClickNode": {"action": "Click"}
                }
                """.trimIndent(),
            ),
        )

        assertNull(spec["Default"])
        assertNull(spec["TemplateMatch"])
        assertNull(spec["Click"])
        assertEquals(3, spec.nodes.size)

        val login = spec["LoginNode"]!!
        assertEquals(RecognitionType.OCR, login.recognition.type)
        assertEquals(0.4, login.recognition.param.optDouble("threshold"), 1e-9)
        assertEquals(2000L, login.rateLimitMs)
        assertEquals(50L, login.preDelayMs)

        val template = spec["TemplateNode"]!!
        assertEquals(RecognitionType.TemplateMatch, template.recognition.type)
        assertEquals(0.6, template.recognition.param.optDouble("threshold"), 1e-9)
        assertEquals(2000L, template.rateLimitMs)

        val click = spec["ClickNode"]!!
        assertEquals(ActionType.Click, click.action.type)
        assertTrue(click.action.param.optBoolean("target"))
    }

    @Test
    fun parsesObjectRoiAsRegionTarget() {
        val param = JSONObject(
            """{"roi":{"x":10,"y":20,"width":30,"height":40},"roi_offset":[1,2]}""",
        )
        val target = PipelineParser.parseTargetOrRoi(param, "roi", "roi_offset", TargetSpec())

        assertEquals(TargetType.Region, target.type)
        assertEquals(RectSpec(10, 20, 30, 40), target.rect)
        assertEquals(1, target.offset[0])
        assertEquals(2, target.offset[1])
    }

    @Test
    fun rejectsDeprecatedFields() {
        for (field in listOf("is_sub", "interrupt")) {
            try {
                PipelineParser.parse("""{"A": {"$field": true}}""")
                fail("应拒绝废弃字段 $field")
            } catch (t: PipelineException) {
                assertTrue(t.message!!.contains("已废弃"))
            }
        }
    }

    @Test
    fun appliesDefaultNodeAndAlgorithmDefaults() {
        val defaults = PipelineDefaults.from(
            JSONObject(
                """
                {
                  "Default": {
                    "rate_limit": 2000,
                    "pre_delay": 50,
                    "recognition": "OCR",
                    "threshold": 0.4
                  },
                  "TemplateMatch": {
                    "recognition": "TemplateMatch",
                    "threshold": 0.6
                  },
                  "Click": {
                    "action": "Click",
                    "target": true
                  }
                }
                """.trimIndent(),
            ),
        )

        val spec = PipelineParser.parse(
            JSONObject(
                """
                {
                  "OcrNode": {"recognition": "OCR", "expected": ["x"]},
                  "TemplateNode": {"recognition": "TemplateMatch", "template": "t.png"},
                  "Inherited": {},
                  "ClickNode": {"action": "Click"}
                }
                """.trimIndent(),
            ),
            defaults,
        )

        val ocr = spec["OcrNode"]!!
        assertEquals(RecognitionType.OCR, ocr.recognition.type)
        assertEquals(0.4, ocr.recognition.param.optDouble("threshold"), 1e-9)
        assertEquals(2000L, ocr.rateLimitMs)
        assertEquals(50L, ocr.preDelayMs)

        val template = spec["TemplateNode"]!!
        assertEquals(RecognitionType.TemplateMatch, template.recognition.type)
        assertEquals(0.6, template.recognition.param.optDouble("threshold"), 1e-9)
        assertEquals("t.png", template.recognition.param.optString("template"))

        val inherited = spec["Inherited"]!!
        assertEquals(RecognitionType.OCR, inherited.recognition.type)
        assertEquals(0.4, inherited.recognition.param.optDouble("threshold"), 1e-9)

        val click = spec["ClickNode"]!!
        assertEquals(ActionType.Click, click.action.type)
        assertTrue(click.action.param.optBoolean("target"))
    }

    @Test
    fun mergesMultipleDefaultBundles() {
        val base = PipelineDefaults.from(
            JSONObject(
                """
                {
                  "Default": {"rate_limit": 1000},
                  "TemplateMatch": {"recognition": "TemplateMatch", "threshold": 0.6},
                  "OCR": {"recognition": "OCR", "threshold": 0.3}
                }
                """.trimIndent(),
            ),
        )
        val later = PipelineDefaults.from(
            JSONObject(
                """
                {
                  "Default": {"timeout": 5000},
                  "TemplateMatch": {"recognition": "TemplateMatch", "threshold": 0.8},
                  "FeatureMatch": {"recognition": "FeatureMatch", "count": 10}
                }
                """.trimIndent(),
            ),
        )
        val merged = base.merge(later)
        val spec = PipelineParser.parse(
            JSONObject(
                """
                {
                  "A": {"recognition": "TemplateMatch", "template": "a.png"},
                  "B": {"recognition": "OCR", "expected": ["x"]},
                  "C": {"recognition": "FeatureMatch", "template": "c.png"}
                }
                """.trimIndent(),
            ),
            merged,
        )

        assertEquals(1000L, spec["A"]!!.rateLimitMs)
        assertEquals(5000L, spec["A"]!!.timeoutMs)
        assertEquals(0.8, spec["A"]!!.recognition.param.optDouble("threshold"), 1e-9)
        assertEquals(0.3, spec["B"]!!.recognition.param.optDouble("threshold"), 1e-9)
        assertEquals(10, spec["C"]!!.recognition.param.optInt("count"))
    }

    @Test
    fun mergesAttachDictionary() {
        val defaults = PipelineDefaults.from(
            JSONObject("""{"Default": {"attach": {"a": 1, "b": 2}}}"""),
        )
        val spec = PipelineParser.parse(
            JSONObject("""{"A": {"attach": {"b": 3, "c": 4}}}"""),
            defaults,
        )

        val attach = spec["A"]!!.attach
        assertEquals(1, attach.optInt("a"))
        assertEquals(3, attach.optInt("b"))
        assertEquals(4, attach.optInt("c"))
    }

    @Test
    fun ignoresDollarRootFields() {
        val spec = PipelineParser.parse("""{"${'$'}schema": "x", "A": {}}""")
        assertNull(spec["${'$'}schema"])
        assertTrue(spec.nodes.containsKey("A"))
    }
}
