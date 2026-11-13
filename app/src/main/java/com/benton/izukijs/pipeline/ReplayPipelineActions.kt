package com.benton.izukijs.pipeline

import com.benton.izukijs.controller.record.AppEvent
import com.benton.izukijs.controller.record.ClickEvent
import com.benton.izukijs.controller.record.InputTextEvent
import com.benton.izukijs.controller.record.LongClickEvent
import com.benton.izukijs.controller.record.PressKeyEvent
import com.benton.izukijs.controller.record.RecordActionKind
import com.benton.izukijs.controller.record.RecordEvent
import com.benton.izukijs.controller.record.RecordingCursor
import com.benton.izukijs.controller.record.RecordingReplay
import com.benton.izukijs.controller.record.ReplayOptions
import com.benton.izukijs.controller.record.ShellEvent
import com.benton.izukijs.controller.record.SwipeEvent
import com.benton.izukijs.vision.Box
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class ReplayClock(var millis: Long = 0L) {
    fun now(): Long = millis

    fun advance(ms: Long) {
        millis += ms.coerceAtLeast(0L)
    }
}

class ReplayPipelineActions(
    private val replay: RecordingReplay,
    private val clock: ReplayClock,
    private val log: (String) -> Unit = {},
) : PipelineActions {

    /** 兼容旧测试 / 调用：宽容模式包装 [RecordingCursor]。 */
    constructor(
        cursor: RecordingCursor,
        clock: ReplayClock,
        log: (String) -> Unit = {},
    ) : this(RecordingReplay(cursor.eventList, ReplayOptions()), clock, log)

    val mismatches: List<String> get() = replay.mismatches.map { it.message }
    val notes: List<String> get() = replay.notes

    override fun isStopRequested(): Boolean = false

    override fun sleep(millis: Long) {
        clock.advance(millis)
    }

    override fun execute(
        node: PipelineNode,
        box: Box?,
        resolveTarget: (TargetSpec, Box?) -> Box?,
    ): PipelineActionResult {
        val param = node.action.param
        return when (node.action.type) {
            ActionType.DoNothing -> PipelineActionResult(true)
            ActionType.StopTask -> PipelineActionResult(true, stopTask = true)

            ActionType.Click -> {
                val target = resolveActionTarget(param, "target", "target_offset", box, resolveTarget)
                    ?: return fail(node, "Click 目标无法解析")
                val event = replay.expectAction(RecordActionKind.CLICK)
                if (event !is ClickEvent) return missing(node, RecordActionKind.CLICK)
                consume(event)
                val matched = abs(event.x - target.centerX) <= tolerance() &&
                    abs(event.y - target.centerY) <= tolerance()
                if (!matched) {
                    val ok = report(
                        node,
                        "Click 坐标不一致，录制=(${event.x},${event.y}) 回放=(${target.centerX},${target.centerY})",
                        "Click",
                        "Click",
                    )
                    if (!ok) return PipelineActionResult(false)
                }
                PipelineActionResult(event.success)
            }

            ActionType.LongPress -> {
                val target = resolveActionTarget(param, "target", "target_offset", box, resolveTarget)
                    ?: return fail(node, "LongPress 目标无法解析")
                val event = replay.expectAction(RecordActionKind.LONG_CLICK)
                if (event !is LongClickEvent) return missing(node, RecordActionKind.LONG_CLICK)
                consume(event)
                val matched = abs(event.x - target.centerX) <= tolerance() &&
                    abs(event.y - target.centerY) <= tolerance()
                if (!matched) {
                    val ok = report(node, "LongPress 坐标不一致", "LongPress", "LongPress")
                    if (!ok) return PipelineActionResult(false)
                }
                PipelineActionResult(event.success)
            }

            ActionType.Swipe -> {
                val expected = resolveSwipeTargets(param, box, resolveTarget)
                    ?: return fail(node, "Swipe 目标无效")
                matchSwipes(node, expected)
            }

            ActionType.MultiSwipe -> {
                val swipes = param.optJSONArray("swipes")
                    ?: return fail(node, "MultiSwipe 缺少 swipes")
                val expected = ArrayList<Pair<Box, Box>>()
                for (index in 0 until swipes.length()) {
                    val item = swipes.optJSONObject(index) ?: continue
                    expected += resolveSwipeTargets(item, box, resolveTarget)
                        ?: return fail(node, "MultiSwipe 第 ${index + 1} 段目标无效")
                }
                matchSwipes(node, expected)
            }

            ActionType.Scroll -> {
                val target = resolveActionTarget(param, "target", "target_offset", box, resolveTarget)
                    ?: return fail(node, "Scroll 目标无效")
                val dx = number(param, "dx") ?: 0.0
                val dy = number(param, "dy") ?: 0.0
                val end = Box(
                    (target.centerX - dx).toInt(),
                    (target.centerY - dy).toInt(),
                    (target.centerX - dx).toInt(),
                    (target.centerY - dy).toInt(),
                )
                matchSwipes(node, listOf(target to end))
            }

            ActionType.InputText -> {
                val text = stringValue(param, "input_text", "inputText")
                val event = replay.expectAction(RecordActionKind.INPUT_TEXT)
                if (event !is InputTextEvent) return missing(node, RecordActionKind.INPUT_TEXT)
                consume(event)
                if (replay.options.compareText && text != null && text != event.text) {
                    val ok = report(
                        node,
                        "InputText 不一致，录制=\"${event.text}\" 回放=\"$text\"",
                        "InputText",
                        "InputText",
                        expectedValue = text,
                        actualValue = event.text,
                    )
                    if (!ok) return PipelineActionResult(false)
                }
                PipelineActionResult(event.success)
            }

            ActionType.StartApp -> {
                val packageName = stringValue(param, "package")
                val event = replay.expectAction(RecordActionKind.APP)
                if (event !is AppEvent) return missing(node, RecordActionKind.APP)
                consume(event)
                if (packageName != null && packageName != event.packageName) {
                    val ok = report(
                        node,
                        "StartApp 包名不一致，录制=${event.packageName} 回放=$packageName",
                        "StartApp",
                        "App",
                        expectedValue = packageName,
                        actualValue = event.packageName,
                    )
                    if (!ok) return PipelineActionResult(false)
                }
                PipelineActionResult(event.success)
            }

            ActionType.StopApp -> {
                val packageName = stringValue(param, "package")
                val event = replay.expectAction(RecordActionKind.SHELL)
                if (event !is ShellEvent) return missing(node, RecordActionKind.SHELL)
                consume(event)
                if (packageName != null && !event.cmd.contains(packageName)) {
                    val ok = report(node, "StopApp 命令与录制不一致", "StopApp", "Shell")
                    if (!ok) return PipelineActionResult(false)
                }
                PipelineActionResult(event.exitCode == 0)
            }

            ActionType.Shell -> {
                val cmd = stringValue(param, "cmd")
                val event = replay.expectAction(RecordActionKind.SHELL)
                if (event !is ShellEvent) return missing(node, RecordActionKind.SHELL)
                consume(event)
                if (cmd != null && cmd != event.cmd) {
                    val ok = report(
                        node,
                        "Shell 命令不一致，录制=\"${event.cmd}\" 回放=\"$cmd\"",
                        "Shell",
                        "Shell",
                        expectedValue = cmd,
                        actualValue = event.cmd,
                    )
                    if (!ok) return PipelineActionResult(false)
                }
                val expectedOut = stringValue(param, "stdout", "expected_stdout")
                if (replay.options.compareShellOutput &&
                    expectedOut != null &&
                    expectedOut != event.stdout
                ) {
                    val ok = report(
                        node,
                        "Shell 输出不一致，录制=\"${event.stdout}\" 回放=\"$expectedOut\"",
                        "Shell",
                        "Shell",
                        expectedValue = expectedOut,
                        actualValue = event.stdout,
                    )
                    if (!ok) return PipelineActionResult(false)
                }
                PipelineActionResult(event.exitCode == 0)
            }

            ActionType.ClickKey, ActionType.LongPressKey, ActionType.KeyDown, ActionType.KeyUp -> {
                val key = keyValue(param)
                val event = replay.expectAction(RecordActionKind.PRESS_KEY)
                if (event !is PressKeyEvent) return missing(node, RecordActionKind.PRESS_KEY)
                consume(event)
                if (key != null && key != event.keyCode) {
                    val ok = report(
                        node,
                        "按键不一致，录制=${event.keyCode} 回放=$key",
                        "Key",
                        "Key",
                        expectedValue = key.toString(),
                        actualValue = event.keyCode.toString(),
                    )
                    if (!ok) return PipelineActionResult(false)
                }
                PipelineActionResult(event.success)
            }

            ActionType.Custom -> {
                replay.addNote("${node.name}: 回放跳过 Custom 动作")
                PipelineActionResult(true)
            }

            ActionType.Command, ActionType.Screencap,
            ActionType.TouchDown, ActionType.TouchMove, ActionType.TouchUp,
            -> {
                replay.addNote("${node.name}: 回放跳过不支持的 ${node.action.type.wireName} 动作")
                PipelineActionResult(true)
            }
        }
    }

    private fun matchSwipes(
        node: PipelineNode,
        expected: List<Pair<Box, Box>>,
    ): PipelineActionResult {
        var success = true
        for ((from, to) in expected) {
            val event = replay.expectAction(RecordActionKind.SWIPE)
            if (event !is SwipeEvent) return missing(node, RecordActionKind.SWIPE)
            consume(event)
            success = success && event.success
            val matched = abs(event.x1 - from.centerX) <= tolerance() &&
                abs(event.y1 - from.centerY) <= tolerance() &&
                abs(event.x2 - to.centerX) <= tolerance() &&
                abs(event.y2 - to.centerY) <= tolerance()
            if (!matched) {
                val ok = report(
                    node,
                    "Swipe 坐标不一致，录制=(${event.x1},${event.y1})→(${event.x2},${event.y2}) " +
                        "回放=(${from.centerX},${from.centerY})→(${to.centerX},${to.centerY})",
                    "Swipe",
                    "Swipe",
                )
                // 宽容模式与 Click / InputText 保持一致：只记录差异，不因此判动作失败。
                if (!ok) return PipelineActionResult(false)
            }
        }
        return PipelineActionResult(success)
    }

    private fun resolveSwipeTargets(
        param: JSONObject,
        box: Box?,
        resolveTarget: (TargetSpec, Box?) -> Box?,
    ): List<Pair<Box, Box>>? {
        val beginParam = if (!param.has("begin") && param.has("starting")) {
            JSONObject(param.toString()).apply { put("begin", param.opt("starting")) }
        } else {
            param
        }
        val begin = resolveActionTarget(beginParam, "begin", "begin_offset", box, resolveTarget)
            ?: return null
        val ends = targetItems(param.opt("end") ?: true)
        if (ends.isEmpty()) return null
        val endOffset = PipelineParser.parseOffset(param, "end_offset", "endOffset")
        val result = ArrayList<Pair<Box, Box>>()
        var from = begin
        for (raw in ends) {
            val parsedEnd = parseTargetValue(raw) ?: return null
            val endTarget = if (endOffset != null) parsedEnd.copy(offset = endOffset) else parsedEnd
            val end = resolveTarget(endTarget, box) ?: return null
            result.add(from to end)
            from = end
        }
        return result
    }

    private fun consume(event: RecordEvent): RecordEvent {
        clock.advance(event.elapsedMs)
        return event
    }

    private fun tolerance(): Int = replay.options.coordinateTolerance

    private fun report(
        node: PipelineNode,
        message: String,
        expected: String,
        actual: String,
        expectedValue: String? = null,
        actualValue: String? = null,
    ): Boolean {
        replay.addMismatch(expected, actual, "${node.name}: $message", expectedValue = expectedValue, actualValue = actualValue)
        log("${node.name}: $message")
        // 宽容模式继续使用录制结果；严格模式立即失败。
        return !replay.isStrict()
    }

    private fun missing(node: PipelineNode, kind: RecordActionKind): PipelineActionResult {
        replay.addMismatch(kind.name, "end", "${node.name}: 回放未找到动作 $kind")
        return PipelineActionResult(false)
    }

    private fun fail(node: PipelineNode, message: String): PipelineActionResult {
        replay.addMismatch("target", "invalid", "${node.name}: $message")
        return PipelineActionResult(false)
    }

    private fun resolveActionTarget(
        param: JSONObject,
        key: String,
        offsetKey: String,
        selfBox: Box?,
        resolveTarget: (TargetSpec, Box?) -> Box?,
    ): Box? {
        val target = try {
            PipelineParser.parseTarget(param, key, offsetKey, TargetSpec())
        } catch (t: Throwable) {
            log("回放目标参数无效: ${t.message}")
            return null
        }
        return resolveTarget(target, selfBox)
    }

    private fun parseTargetValue(raw: Any?): TargetSpec? = try {
        PipelineParser.parseTarget(JSONObject().put("value", raw), "value", "value_offset", TargetSpec())
    } catch (t: Throwable) {
        null
    }

    private fun targetItems(raw: Any?): List<Any?> =
        if (raw is JSONArray && !isRectArray(raw)) {
            (0 until raw.length()).map { raw.opt(it) }
        } else {
            listOf(raw)
        }

    private fun isRectArray(value: JSONArray): Boolean {
        if (value.length() != 2 && value.length() != 4) return false
        for (index in 0 until value.length()) {
            if (value.opt(index) !is Number) return false
        }
        return true
    }

    private fun stringValue(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = obj.opt(key)
            if (value is String && value.isNotBlank()) return value
        }
        return null
    }

    private fun number(obj: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            val value = obj.opt(key)
            if (value is Number) return value.toDouble()
        }
        return null
    }

    private fun keyValue(obj: JSONObject): Int? {
        val raw = obj.opt("key") ?: obj.opt("keys") ?: return null
        return when (raw) {
            is Number -> raw.toInt()
            is JSONArray -> (raw.opt(0) as? Number)?.toInt()
            else -> null
        }
    }
}
