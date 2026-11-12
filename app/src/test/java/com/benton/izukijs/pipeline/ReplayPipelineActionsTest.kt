package com.benton.izukijs.pipeline

import com.benton.izukijs.controller.record.ClickEvent
import com.benton.izukijs.controller.record.InputTextEvent
import com.benton.izukijs.controller.record.PressKeyEvent
import com.benton.izukijs.controller.record.RecordingCursor
import com.benton.izukijs.controller.record.ShellEvent
import com.benton.izukijs.vision.Box
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayPipelineActionsTest {

    private val selfResolver: (TargetSpec, Box?) -> Box? = { target, self ->
        if (target.type == TargetType.Self) self else null
    }

    @Test
    fun verifiesClickAndAdvancesClock() {
        val cursor = RecordingCursor(listOf(ClickEvent(0, 10, 20, true)))
        val clock = ReplayClock()
        val actions = ReplayPipelineActions(cursor, clock)
        val node = node(ActionType.Click, JSONObject().put("target", true))

        val result = actions.execute(node, Box(0, 0, 20, 40), selfResolver)

        assertTrue(result.success)
        assertTrue(actions.notes.isEmpty())
        actions.sleep(120)
        assertEquals(120, clock.now())
    }

    @Test
    fun notesTextMismatchButUsesRecordedResult() {
        val cursor = RecordingCursor(listOf(InputTextEvent(0, "bye", "AUTO", true)))
        val actions = ReplayPipelineActions(cursor, ReplayClock())
        val node = node(ActionType.InputText, JSONObject().put("input_text", "hi"))

        val result = actions.execute(node, null, selfResolver)

        assertTrue(result.success)
        assertTrue(actions.mismatches.any { it.contains("InputText 不一致") })
    }

    @Test
    fun failsWhenActionMissing() {
        val actions = ReplayPipelineActions(RecordingCursor(emptyList()), ReplayClock())
        val node = node(ActionType.Click, JSONObject().put("target", true))

        val result = actions.execute(node, Box(0, 0, 10, 10), selfResolver)

        assertFalse(result.success)
        assertTrue(actions.mismatches.any { it.contains("未找到动作 CLICK") })
    }

    @Test
    fun matchesKeyAndShell() {
        val keyActions = ReplayPipelineActions(
            RecordingCursor(listOf(PressKeyEvent(0, 4, true))),
            ReplayClock(),
        )
        val keyNode = node(ActionType.ClickKey, JSONObject().put("key", 4))
        assertTrue(keyActions.execute(keyNode, null, selfResolver).success)

        val shellActions = ReplayPipelineActions(
            RecordingCursor(listOf(ShellEvent(0, "echo hi", "hi", "", 0, true))),
            ReplayClock(),
        )
        val shellNode = node(ActionType.Shell, JSONObject().put("cmd", "echo hi"))
        assertTrue(shellActions.execute(shellNode, null, selfResolver).success)
        assertTrue(shellActions.notes.isEmpty())
    }

    @Test
    fun stopTaskReturnsStopTask() {
        val actions = ReplayPipelineActions(RecordingCursor(emptyList()), ReplayClock())
        val result = actions.execute(node(ActionType.StopTask, JSONObject()), null, selfResolver)

        assertTrue(result.success)
        assertTrue(result.stopTask)
    }

    private fun node(type: ActionType, param: JSONObject): PipelineNode = PipelineNode(
        name = "A",
        action = ActionSpec(type, param),
    )
}
