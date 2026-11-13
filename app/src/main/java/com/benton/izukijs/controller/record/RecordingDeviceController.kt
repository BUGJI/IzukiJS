package com.benton.izukijs.controller.record

import android.graphics.Bitmap
import com.benton.izukijs.controller.DeviceController
import com.benton.izukijs.controller.GestureStroke
import com.benton.izukijs.controller.NodeSnapshot
import com.benton.izukijs.controller.ShellResult
import com.benton.izukijs.controller.TextInputMethod
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import org.json.JSONArray
import org.json.JSONObject

class RecordingDeviceController(
    private val inner: DeviceController,
    private val writer: RecordingWriter,
    private val source: String = RecordSource.SCRIPT,
) : DeviceController {

    override val mode: ControlMode get() = inner.mode

    override fun capabilities(): Set<Capability> = inner.capabilities()

    override fun isReady(): Boolean = inner.isReady()

    override fun supportsDisplayTarget(): Boolean = inner.supportsDisplayTarget()

    override fun click(x: Float, y: Float): Boolean = timed(
        build = { atMs, elapsed, success ->
            ClickEvent(atMs, x.toInt(), y.toInt(), success, source, elapsedMs = elapsed)
        },
    ) { inner.click(x, y) }

    override fun longClick(x: Float, y: Float, durationMs: Long): Boolean = timed(
        build = { atMs, elapsed, success ->
            LongClickEvent(atMs, x.toInt(), y.toInt(), durationMs, success, source, elapsedMs = elapsed)
        },
    ) { inner.longClick(x, y, durationMs) }

    override fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
    ): Boolean = timed(
        build = { atMs, elapsed, success ->
            SwipeEvent(
                timestamp = atMs,
                x1 = x1.toInt(),
                y1 = y1.toInt(),
                x2 = x2.toInt(),
                y2 = y2.toInt(),
                durationMs = durationMs,
                success = success,
                source = source,
                elapsedMs = elapsed,
            )
        },
    ) { inner.swipe(x1, y1, x2, y2, durationMs) }

    override fun supportsGesture(): Boolean = inner.supportsGesture()

    override fun gesture(strokes: List<GestureStroke>): Boolean = timed(
        build = { atMs, elapsed, success ->
            GestureEvent(atMs, strokesToJson(strokes), success, source, elapsedMs = elapsed)
        },
    ) { inner.gesture(strokes) }

    override fun pressKey(keyCode: Int): Boolean = timed(
        build = { atMs, elapsed, success ->
            PressKeyEvent(atMs, keyCode, success, source, elapsedMs = elapsed)
        },
    ) { inner.pressKey(keyCode) }

    override fun inputText(text: String): Boolean = timed(
        build = { atMs, elapsed, success ->
            InputTextEvent(atMs, text, TextInputMethod.AUTO.name, success, source, elapsedMs = elapsed)
        },
    ) { inner.inputText(text) }

    override fun inputText(text: String, method: TextInputMethod): Boolean = timed(
        build = { atMs, elapsed, success ->
            InputTextEvent(atMs, text, method.name, success, source, elapsedMs = elapsed)
        },
    ) { inner.inputText(text, method) }

    override fun globalAction(action: Int): Boolean = timed(
        build = { atMs, elapsed, success ->
            GlobalActionEvent(atMs, action, success, source, elapsedMs = elapsed)
        },
    ) { inner.globalAction(action) }

    override fun lastClipboardRestored(): Boolean? = inner.lastClipboardRestored()

    override fun shell(cmd: String): ShellResult? {
        val startedWall = System.currentTimeMillis()
        val result = inner.shell(cmd)
        val elapsed = (System.currentTimeMillis() - startedWall).coerceAtLeast(0L)
        if (!RecordingSuppression.isSuppressed()) {
            writer.record(
                ShellEvent(
                    timestamp = writer.timestampOf(startedWall),
                    cmd = cmd,
                    stdout = truncateShellOutput(result?.stdout.orEmpty()),
                    stderr = truncateShellOutput(result?.stderr.orEmpty()),
                    exitCode = result?.exitCode ?: -1,
                    success = result != null,
                    source = source,
                    elapsedMs = elapsed,
                ),
            )
        }
        return result
    }

    override fun screenshot(): Bitmap? = inner.screenshot()

    override fun screenshotForDisplay(displayId: Int?): Bitmap? =
        inner.screenshotForDisplay(displayId)

    override fun nodeTree(): NodeSnapshot? = inner.nodeTree()

    private inline fun timed(
        build: (atMs: Long, elapsedMs: Long, success: Boolean) -> RecordEvent,
        action: () -> Boolean,
    ): Boolean {
        val startedWall = System.currentTimeMillis()
        val success = action()
        val elapsed = (System.currentTimeMillis() - startedWall).coerceAtLeast(0L)
        writer.record(build(writer.timestampOf(startedWall), elapsed, success))
        return success
    }

    private fun truncateShellOutput(value: String): String =
        if (value.length <= MAX_SHELL_OUTPUT_CHARS) {
            value
        } else {
            value.take(MAX_SHELL_OUTPUT_CHARS) + "\n…[truncated]"
        }

    private fun strokesToJson(strokes: List<GestureStroke>): JSONArray {
        val array = JSONArray()
        for (stroke in strokes) {
            val points = JSONArray()
            for (point in stroke.points) {
                points.put(
                    JSONObject()
                        .put("x", point.x)
                        .put("y", point.y)
                        .put("t", point.timeMs),
                )
            }
            array.put(points)
        }
        return array
    }

    private companion object {
        const val MAX_SHELL_OUTPUT_CHARS = 8_192
    }
}
