package com.benton.izukijs.pipeline

import android.graphics.Bitmap
import com.benton.izukijs.vision.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineRunnerTest {

    @Test
    fun runsEntryAndFollowsNext() {
        val spec = PipelineParser.parse(
            """
            {
              "A": {"next": ["B"]},
              "B": {}
            }
            """.trimIndent(),
        )
        val actions = FakeActions()
        val result = runner(spec, FakeVision(), actions).run("A")

        assertTrue(result.ok)
        assertEquals(2, result.steps)
        assertEquals("B", result.lastNode)
        assertEquals(listOf("A", "B"), actions.executed)
    }

    @Test
    fun entersOnErrorWhenNextMisses() {
        val spec = PipelineParser.parse(
            """
            {
              "A": {
                "next": ["Miss"],
                "on_error": ["Recover"],
                "timeout": 0,
                "rate_limit": 0
              },
              "Miss": {"recognition": "DirectHit"},
              "Recover": {}
            }
            """.trimIndent(),
        )
        val actions = FakeActions()
        val result = runner(
            spec,
            FakeVision(hits = { node -> if (node.name == "Miss") null else Box(0, 0, 10, 10) }),
            actions,
        ).run("A")

        assertTrue(result.ok)
        assertEquals("Recover", result.lastNode)
        assertEquals(listOf("A", "Recover"), actions.executed)
    }

    @Test
    fun jumpBackReturnsToParentAndRespectsMaxHit() {
        val spec = PipelineParser.parse(
            """
            {
              "A": {"next": ["[JumpBack]Popup", "Done"]},
              "Popup": {"max_hit": 1, "next": ["PopupDone"]},
              "PopupDone": {},
              "Done": {}
            }
            """.trimIndent(),
        )
        val actions = FakeActions()
        val result = runner(spec, FakeVision(), actions).run("A")

        assertTrue(result.ok)
        assertEquals("Done", result.lastNode)
        assertEquals(4, result.steps)
        assertEquals(1, result.hitCounts["Popup"])
        assertEquals(listOf("A", "Popup", "PopupDone", "Done"), actions.executed)
    }

    @Test
    fun failsFastWhenAllCandidatesReachMaxHit() {
        val spec = PipelineParser.parse(
            """
            {
              "A": {"next": ["Popup"]},
              "Popup": {"max_hit": 1, "next": ["Popup"]}
            }
            """.trimIndent(),
        )
        val result = runner(spec, FakeVision(), FakeActions()).run("A")

        assertFalse(result.ok)
        assertEquals("Popup", result.lastNode)
        assertEquals(2, result.steps)
    }

    @Test
    fun stopTaskEndsSuccessfully() {
        val spec = PipelineParser.parse(
            """
            {
              "A": {"action": "StopTask", "next": ["B"]},
              "B": {}
            }
            """.trimIndent(),
        )
        val actions = FakeActions(
            onExecute = { node, _ ->
                if (node.action.type == ActionType.StopTask) {
                    PipelineActionResult(true, stopTask = true)
                } else {
                    PipelineActionResult(true)
                }
            },
        )
        val result = runner(spec, FakeVision(), actions).run("A")

        assertTrue(result.ok)
        assertTrue(result.taskStopped)
        assertEquals(1, result.steps)
    }

    @Test
    fun scriptExitStopsRunner() {
        val spec = PipelineParser.parse("""{"A": {"next": ["B"]}, "B": {}}""")
        val actions = FakeActions(stop = true)
        val result = runner(spec, FakeVision(), actions).run("A")

        assertTrue(result.ok)
        assertTrue(result.stopped)
        assertEquals(0, result.steps)
    }

    @Test
    fun inverseTurnsMissIntoHit() {
        val spec = PipelineParser.parse(
            """
            {
              "A": {"inverse": true, "action": "Click", "next": []}
            }
            """.trimIndent(),
        )
        var receivedBox: Box? = Box(0, 0, 1, 1)
        val actions = FakeActions(
            onExecute = { _, box ->
                receivedBox = box
                PipelineActionResult(true)
            },
        )
        val result = runner(spec, FakeVision(hits = { null }), actions).run("A")

        assertTrue(result.ok)
        assertEquals(1, result.steps)
        assertEquals(1, result.hitCounts["A"])
        assertEquals(null, receivedBox)
    }

    @Test
    fun resolvesAnchorAndPreTaskTargets() {
        val spec = PipelineParser.parse(
            """
            {
              "A": {"anchor": "X", "next": ["B"]},
              "B": {"next": []}
            }
            """.trimIndent(),
        )
        val boxes = mapOf(
            "A" to Box(10, 20, 40, 60),
            "B" to Box(50, 60, 120, 140),
        )
        var anchorBox: Box? = null
        var preTaskBox: Box? = null
        val actions = object : PipelineActions {
            override fun execute(
                node: PipelineNode,
                box: Box?,
                resolveTarget: (TargetSpec, Box?) -> Box?,
            ): PipelineActionResult {
                if (node.name == "B") {
                    anchorBox = resolveTarget(TargetSpec(TargetType.Anchor, ref = "X"), box)
                    preTaskBox = resolveTarget(TargetSpec(TargetType.PreTask, ref = "A"), box)
                }
                return PipelineActionResult(true)
            }

            override fun sleep(millis: Long) = Unit

            override fun isStopRequested(): Boolean = false
        }
        val vision = object : PipelineVision {
            override fun screenWidth(): Int = 200

            override fun screenHeight(): Int = 200

            override fun capture(): Bitmap? = null

            override fun recognize(
                node: PipelineNode,
                image: Bitmap?,
                resolveNode: (String) -> PipelineNode?,
                resolveTarget: (TargetSpec, Box?) -> Box?,
            ): Box? = boxes[node.name]

            override fun framesEqual(lhs: Bitmap, rhs: Bitmap, roi: Box, threshold: Double, method: Int): Boolean = true
        }

        val result = runner(spec, vision, actions).run("A")

        assertTrue(result.ok)
        assertEquals(Box(10, 20, 40, 60), anchorBox)
        assertEquals(Box(10, 20, 40, 60), preTaskBox)
    }

    @Test
    fun reportsMissingEntry() {
        val spec = PipelineParser.parse("""{"A": {}}""")
        val result = runner(spec, FakeVision(), FakeActions()).run("Missing")

        assertFalse(result.ok)
        assertTrue(result.error!!.contains("入口节点不存在"))
    }

    private fun runner(
        spec: PipelineSpec,
        vision: PipelineVision,
        actions: PipelineActions,
    ): PipelineRunner {
        var clock = 0L
        return PipelineRunner(
            spec = spec,
            vision = vision,
            actions = actions,
            now = { clock += 10; clock },
        )
    }

    private class FakeVision(
        private val hits: (PipelineNode) -> Box? = { Box(0, 0, 10, 10) },
    ) : PipelineVision {
        override fun screenWidth(): Int = 100

        override fun screenHeight(): Int = 100

        override fun capture(): Bitmap? = null

        override fun recognize(
            node: PipelineNode,
            image: Bitmap?,
            resolveNode: (String) -> PipelineNode?,
            resolveTarget: (TargetSpec, Box?) -> Box?,
        ): Box? = hits(node)

        override fun framesEqual(lhs: Bitmap, rhs: Bitmap, roi: Box, threshold: Double, method: Int): Boolean = true
    }

    private class FakeActions(
        var stop: Boolean = false,
        private val onExecute: (PipelineNode, Box?) -> PipelineActionResult =
            { _, _ -> PipelineActionResult(true) },
    ) : PipelineActions {
        val executed = ArrayList<String>()

        override fun execute(
            node: PipelineNode,
            box: Box?,
            resolveTarget: (TargetSpec, Box?) -> Box?,
        ): PipelineActionResult {
            executed.add(node.name)
            return onExecute(node, box)
        }

        override fun sleep(millis: Long) = Unit

        override fun isStopRequested(): Boolean = stop
    }
}
