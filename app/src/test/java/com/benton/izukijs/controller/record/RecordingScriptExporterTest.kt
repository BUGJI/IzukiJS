package com.benton.izukijs.controller.record

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingScriptExporterTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun reader(events: List<RecordEvent>, folder: String = "rec"): RecordingReader {
        val dir = temporary.newFolder(folder)
        val meta = RecordingMeta(id = folder, name = "demo", complete = true)
        return RecordingReader(meta, dir, events)
    }

    @Test
    fun screenshotsAndContextsDoNotProduceSleep() {
        val actions = listOf(
            ScreenshotEvent(timestamp = 10, frame = "frames/1.jpg", width = 100, height = 200),
            ClickEvent(timestamp = 20, x = 1, y = 2, success = true),
            ContextEvent(timestamp = 900, refSeq = 2, packageName = "com.example"),
            ScreenshotEvent(timestamp = 950, frame = "frames/2.jpg", width = 100, height = 200),
            ClickEvent(timestamp = 1_000, x = 3, y = 4, success = true),
        )

        val script = RecordingScriptExporter.build(reader(actions))

        // 两个动作之间只差 980ms，但中间的截图 / context 不应制造额外 sleep。
        assertTrue(script.contains("sleep(980);"))
        assertFalse(script.contains("sleep(880);"))
        assertFalse(script.contains("sleep(50);"))
    }

    @Test
    fun exportsGlobalActionAndKey() {
        val script = RecordingScriptExporter.build(
            reader(
                listOf(
                    GlobalActionEvent(timestamp = 100, action = 1, success = true),
                    PressKeyEvent(timestamp = 400, keyCode = 4, success = true),
                ),
            ),
        )

        assertTrue(script.contains("back();"))
        assertTrue(script.contains("press(4);"))
    }

    @Test
    fun prefersTextThenIdForUserTaps() {
        val withText = RecordingScriptExporter.build(
            reader(
                folder = "rec-text",
                events = listOf(
                    UserActionEvent(
                        timestamp = 10,
                        kind = UserActionKind.TAP,
                        x = 1,
                        y = 2,
                        targets = listOf(
                            UiTarget(
                                className = null,
                                text = "登录",
                                viewId = "btn_login",
                                contentDescription = null,
                                left = 0,
                                top = 0,
                                right = 1,
                                bottom = 1,
                                clickable = true,
                                editable = false,
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(withText.contains("selector.clickByText(\"登录\");"))

        val withId = RecordingScriptExporter.build(
            reader(
                folder = "rec-id",
                events = listOf(
                    UserActionEvent(
                        timestamp = 10,
                        kind = UserActionKind.TAP,
                        x = 1,
                        y = 2,
                        targets = listOf(
                            UiTarget(
                                className = null,
                                text = null,
                                viewId = "btn_login",
                                contentDescription = null,
                                left = 0,
                                top = 0,
                                right = 1,
                                bottom = 1,
                                clickable = true,
                                editable = false,
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(withId.contains("selector.clickById(\"btn_login\");"))
    }

    @Test
    fun warnsWhenRecordingIsIncomplete() {
        val events = listOf(ClickEvent(timestamp = 10, x = 1, y = 2, success = true))
        val dir = temporary.newFolder("rec2")
        val meta = RecordingMeta(id = "rec2", name = "demo", complete = false)
        val script = RecordingScriptExporter.build(RecordingReader(meta, dir, events))
        assertTrue(script.contains("没有正常结束"))
    }

    @Test
    fun warnsWhenFramesWereTruncated() {
        val dir = temporary.newFolder("rec-truncated")
        val meta = RecordingMeta(id = "rec-truncated", name = "demo", complete = true, truncated = true)
        val script = RecordingScriptExporter.build(
            RecordingReader(meta, dir, listOf(ClickEvent(timestamp = 10, x = 1, y = 2, success = true))),
        )
        assertTrue(script.contains("达到帧数上限"))
    }

    @Test
    fun frameCommentDoesNotDependOnPackageName() {
        val dir = temporary.newFolder("rec-frame")
        val meta = RecordingMeta(id = "rec-frame", name = "demo", complete = true)
        val script = RecordingScriptExporter.build(
            RecordingReader(
                meta,
                dir,
                listOf(
                    UserActionEvent(
                        timestamp = 10,
                        kind = UserActionKind.TAP,
                        x = 1,
                        y = 2,
                        packageName = "",
                        beforeFrame = "frames/before.jpg",
                        afterFrame = "frames/after.jpg",
                    ),
                ),
            ),
        )

        assertTrue(script.contains("// frame: frames/before.jpg"))
        assertTrue(script.contains("// afterFrame: frames/after.jpg"))
    }

    @Test
    fun escapesTextAndFlattensShellComments() {
        val script = RecordingScriptExporter.build(
            reader(
                listOf(
                    InputTextEvent(timestamp = 10, text = "a\"b\nc", method = "AUTO", success = true),
                    ShellEvent(
                        timestamp = 200,
                        cmd = "echo 1\necho 2",
                        stdout = "",
                        stderr = "",
                        exitCode = 0,
                        success = true,
                    ),
                ),
            ),
        )
        assertTrue(script.contains("input(\"a\\\"b\\nc\");"))
        assertTrue(script.contains("// shell: echo 1 echo 2"))
        assertFalse(script.contains("echo 1\necho 2"))
    }

    @Test
    fun includesGestureJsonAsArrayLiteral() {
        val strokes = JSONArray().put(JSONArray().put(JSONObject().put("x", 1).put("y", 2).put("t", 0)))
        val script = RecordingScriptExporter.build(
            reader(
                listOf(
                    UserActionEvent(
                        timestamp = 10,
                        kind = UserActionKind.GESTURE,
                        strokes = strokes,
                        screenWidth = 100,
                        screenHeight = 200,
                    ),
                ),
            ),
        )
        assertTrue(script.contains("gesture([["))
        assertTrue(script.contains("\"x\":1"))
    }
}
