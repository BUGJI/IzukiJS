package com.benton.izukijs.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineDumperTest {

    @Test
    fun roundTripsV1Node() {
        val original = PipelineParser.parse(
            """
            {
              "A": {
                "recognition": "TemplateMatch",
                "template": "a.png",
                "threshold": 0.9,
                "roi": [10, 20, 30, 40],
                "action": "Click",
                "target": [100, 200],
                "next": ["B", "[JumpBack]C", {"name": "D", "anchor": true}],
                "on_error": ["E"],
                "anchor": {"X": "A"},
                "rate_limit": 500,
                "timeout": 3000,
                "pre_delay": 100,
                "post_delay": 200,
                "pre_wait_freezes": 300,
                "post_wait_freezes": {
                  "time": 150,
                  "target": "[Anchor]X",
                  "target_offset": [1, 2, 3, 4],
                  "threshold": 0.9,
                  "method": 1,
                  "rate_limit": 50,
                  "timeout": 1000
                },
                "repeat": 2,
                "repeat_delay": 50,
                "max_hit": 5,
                "focus": {"start": "ui"},
                "attach": {"tag": "demo"}
              },
              "B": {},
              "C": {},
              "D": {},
              "E": {}
            }
            """.trimIndent(),
        )

        val dumped = PipelineDumper.dump(original)
        val roundTripped = PipelineParser.parse(dumped)

        assertJsonEquals(original["A"]!!.recognition.param, roundTripped["A"]!!.recognition.param)
        assertJsonEquals(original["A"]!!.action.param, roundTripped["A"]!!.action.param)
        assertEquals(original["A"]!!.next, roundTripped["A"]!!.next)
        assertEquals(original["A"]!!.onError, roundTripped["A"]!!.onError)
        assertEquals(original["A"]!!.anchors, roundTripped["A"]!!.anchors)
        assertEquals(500L, roundTripped["A"]!!.rateLimitMs)
        assertEquals(3000L, roundTripped["A"]!!.timeoutMs)
        assertEquals(300L, roundTripped["A"]!!.preWaitFreezes.timeMs)
        assertEquals(TargetType.Anchor, roundTripped["A"]!!.postWaitFreezes.target.type)
        assertEquals(150L, roundTripped["A"]!!.postWaitFreezes.timeMs)
        assertEquals(2, roundTripped["A"]!!.repeat)
        assertEquals(5, roundTripped["A"]!!.maxHit)
        assertEquals("ui", roundTripped["A"]!!.focus?.optString("start"))
        assertEquals("demo", roundTripped["A"]!!.attach.optString("tag"))
    }

    @Test
    fun dumpsV2Shape() {
        val spec = PipelineParser.parse(
            """
            {
              "A": {
                "recognition": "OCR",
                "expected": ["OK"],
                "action": "InputText",
                "input_text": "hello",
                "next": ["B"]
              },
              "B": {}
            }
            """.trimIndent(),
        )

        val dumped = PipelineDumper.dump(spec)
        val node = dumped.getJSONObject("A")
        assertEquals("OCR", node.getJSONObject("recognition").getString("type"))
        assertEquals("OK", node.getJSONObject("recognition").getJSONObject("param").getJSONArray("expected").getString(0))
        assertEquals("InputText", node.getJSONObject("action").getString("type"))
        assertEquals("hello", node.getJSONObject("action").getJSONObject("param").getString("input_text"))
        assertEquals("B", node.getJSONArray("next").getString(0))
    }

    @Test
    fun dumpsNodeAttributes() {
        val plain = PipelineDumper.dumpNodeAttr(NodeAttr("B"))
        assertEquals("B", plain)

        val jumpBack = PipelineDumper.dumpNodeAttr(NodeAttr("C", jumpBack = true)) as org.json.JSONObject
        assertEquals("C", jumpBack.getString("name"))
        assertTrue(jumpBack.getBoolean("jump_back"))

        val anchor = PipelineDumper.dumpNodeAttr(NodeAttr("D", anchorRef = true)) as org.json.JSONObject
        assertEquals("D", anchor.getString("name"))
        assertTrue(anchor.getBoolean("anchor"))
    }

    @Test
    fun dumpsTargetKinds() {
        assertEquals(true, PipelineDumper.dumpTarget(TargetSpec()))
        assertEquals("NodeA", PipelineDumper.dumpTarget(TargetSpec(type = TargetType.PreTask, ref = "NodeA")))
        assertEquals("[Anchor]X", PipelineDumper.dumpTarget(TargetSpec(type = TargetType.Anchor, ref = "X")))

        val region = PipelineDumper.dumpTarget(
            TargetSpec(
                type = TargetType.Region,
                rect = com.benton.izukijs.vision.RectSpec(1, 2, 3, 4),
            ),
        ) as org.json.JSONArray
        assertEquals(1, region.getInt(0))
        assertEquals(4, region.getInt(3))
    }
}
