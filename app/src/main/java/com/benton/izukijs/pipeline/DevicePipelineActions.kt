package com.benton.izukijs.pipeline

import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.runtime.api.AppApi
import com.benton.izukijs.runtime.api.InputApi
import com.benton.izukijs.runtime.api.ShellApi
import com.benton.izukijs.vision.Box
import org.json.JSONArray
import org.json.JSONObject

class DevicePipelineActions(
    private val inputApi: InputApi,
    private val appApi: AppApi,
    private val shellApi: ShellApi,
    private val logBus: LogBus,
    private val isExitRequested: () -> Boolean,
    private val customAction: (String, JSONObject, Box?) -> Boolean = { _, _, _ -> false },
) : PipelineActions {

    override fun isStopRequested(): Boolean = isExitRequested()

    override fun sleep(millis: Long) {
        var remaining = millis.coerceAtLeast(0L)
        while (remaining > 0L) {
            if (isExitRequested()) return
            val step = minOf(remaining, SLEEP_SLICE_MS)
            Thread.sleep(step)
            remaining -= step
        }
    }

    override fun execute(
        node: PipelineNode,
        box: Box?,
        resolveTarget: (TargetSpec, Box?) -> Box?,
    ): PipelineActionResult {
        val param = node.action.param
        return when (node.action.type) {
            ActionType.DoNothing -> PipelineActionResult(true)

            ActionType.Click -> {
                val target = resolveBox(param, "target", "target_offset", box, resolveTarget)
                    ?: return fail(node, "Click 目标无效")
                result(node, inputApi.click(target.centerX.toDouble(), target.centerY.toDouble()))
            }

            ActionType.LongPress -> {
                val target = resolveBox(param, "target", "target_offset", box, resolveTarget)
                    ?: return fail(node, "LongPress 目标无效")
                val duration = number(param, "duration") ?: 1000.0
                result(
                    node,
                    inputApi.longClick(target.centerX.toDouble(), target.centerY.toDouble(), duration),
                )
            }

            ActionType.Swipe -> swipe(node, param, box, resolveTarget)

            ActionType.MultiSwipe -> {
                val swipes = param.optJSONArray("swipes")
                    ?: return fail(node, "MultiSwipe 缺少 swipes")
                for (index in 0 until swipes.length()) {
                    val item = swipes.optJSONObject(index) ?: continue
                    val outcome = swipe(node, item, box, resolveTarget)
                    if (!outcome.success) return outcome
                }
                PipelineActionResult(true)
            }

            ActionType.InputText -> {
                val text = stringValue(param, "input_text", "inputText")
                    ?: return fail(node, "InputText 缺少 input_text")
                result(node, inputApi.input(text))
            }

            ActionType.StartApp -> {
                val packageName = stringValue(param, "package")
                    ?: return fail(node, "StartApp 缺少 package")
                result(node, appApi.launch(packageName))
            }

            ActionType.StopApp -> {
                val packageName = stringValue(param, "package")
                    ?: return fail(node, "StopApp 缺少 package")
                result(node, shellApi.run("am force-stop $packageName") != null)
            }

            ActionType.Shell -> {
                val cmd = stringValue(param, "cmd")
                    ?: return fail(node, "Shell 缺少 cmd")
                val outcome = shellApi.run(cmd)
                result(node, outcome != null && outcome.exitCode == 0)
            }

            ActionType.Command -> fail(node, "Command 动作仅桌面端支持")

            ActionType.ClickKey -> pressKeys(node, param)

            // 底层 DeviceController 只提供 pressKey，没有按下/抬起或长按状态；
            // 继续把三者当成普通点击会产生误导，明确失败并提示。
            ActionType.LongPressKey, ActionType.KeyDown, ActionType.KeyUp ->
                fail(node, "${node.action.type.wireName} 暂未支持（底层仅支持离散按键）")

            ActionType.Scroll -> {
                val target = resolveBox(param, "target", "target_offset", box, resolveTarget)
                    ?: return fail(node, "Scroll 目标无效")
                val dx = number(param, "dx") ?: 0.0
                val dy = number(param, "dy") ?: 0.0
                val cx = target.centerX.toDouble()
                val cy = target.centerY.toDouble()
                result(node, inputApi.swipe(cx, cy, cx - dx, cy - dy, 200.0))
            }

            ActionType.StopTask -> PipelineActionResult(true, stopTask = true)

            ActionType.TouchDown, ActionType.TouchMove, ActionType.TouchUp ->
                fail(node, "${node.action.type.wireName} 动作暂未支持")

            ActionType.Screencap -> fail(node, "Screencap 动作暂未支持")

            ActionType.Custom -> {
                val name = stringValue(param, "custom_action", "customAction")
                    ?: return fail(node, "Custom 动作缺少 custom_action")
                val target = if (param.has("target")) {
                    resolveBox(param, "target", "target_offset", box, resolveTarget)
                } else {
                    box
                }
                val extra = param.opt("custom_action_param")
                    ?: param.opt("customActionParam")
                    ?: JSONObject()
                val extraObject = extra as? JSONObject ?: JSONObject().put("value", extra)
                result(node, customAction(name, extraObject, target))
            }
        }
    }

    private fun swipe(
        node: PipelineNode,
        param: JSONObject,
        box: Box?,
        resolveTarget: (TargetSpec, Box?) -> Box?,
    ): PipelineActionResult {
        // Maa 配置里起点写作 starting；运行时统一按 begin 读取。
        val beginParam = if (!param.has("begin") && param.has("starting")) {
            JSONObject(param.toString()).apply { put("begin", param.opt("starting")) }
        } else {
            param
        }
        val begin = resolveBox(beginParam, "begin", "begin_offset", box, resolveTarget)
            ?: return fail(node, "Swipe 起点无效")
        val ends = targetItems(param.opt("end") ?: true)
        if (ends.isEmpty()) return fail(node, "Swipe 终点为空")
        val durations = numberList(param.opt("duration"))
        val endOffset = PipelineParser.parseOffset(param, "end_offset", "endOffset")

        var from = begin
        for ((index, raw) in ends.withIndex()) {
            val parsedEnd = parseTargetValue(raw)
                ?: return fail(node, "Swipe 终点无效")
            val endTarget = if (endOffset != null) parsedEnd.copy(offset = endOffset) else parsedEnd
            val end = resolveTarget(endTarget, box)
                ?: return fail(node, "Swipe 终点无法解析")
            val duration = durations.getOrNull(index) ?: durations.lastOrNull() ?: 200.0
            val success = inputApi.swipe(
                from.centerX.toDouble(),
                from.centerY.toDouble(),
                end.centerX.toDouble(),
                end.centerY.toDouble(),
                duration,
            )
            if (!success) return fail(node, "Swipe 执行失败")
            from = end
        }
        return PipelineActionResult(true)
    }

    private fun pressKeys(node: PipelineNode, param: JSONObject): PipelineActionResult {
        val keys = keyValues(param.opt("key") ?: param.opt("keys"))
        if (keys.isEmpty()) return fail(node, "${node.action.type.wireName} 缺少 key")
        for (key in keys) {
            if (!inputApi.press(key)) return fail(node, "按键 $key 失败")
        }
        return PipelineActionResult(true)
    }

    private fun resolveBox(
        param: JSONObject,
        key: String,
        offsetKey: String,
        selfBox: Box?,
        resolveTarget: (TargetSpec, Box?) -> Box?,
    ): Box? {
        val target = try {
            PipelineParser.parseTarget(param, key, offsetKey, TargetSpec())
        } catch (t: Throwable) {
            logBus.warn("Pipeline 动作目标参数无效: ${t.message}")
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

    private fun keyValues(raw: Any?): List<Int> = when (raw) {
        is Number -> listOf(raw.toInt())
        is JSONArray -> (0 until raw.length()).mapNotNull { index ->
            (raw.opt(index) as? Number)?.toInt()
        }

        else -> emptyList()
    }

    private fun numberList(raw: Any?): List<Double> = when (raw) {
        is Number -> listOf(raw.toDouble())
        is JSONArray -> (0 until raw.length()).mapNotNull { index ->
            (raw.opt(index) as? Number)?.toDouble()
        }

        else -> emptyList()
    }

    private fun number(obj: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            val value = obj.opt(key)
            if (value is Number) return value.toDouble()
        }
        return null
    }

    private fun stringValue(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = obj.opt(key)
            if (value is String && value.isNotBlank()) return value
        }
        return null
    }

    private fun result(node: PipelineNode, success: Boolean): PipelineActionResult {
        if (!success) logBus.warn("Pipeline 节点 ${node.name} 动作失败: ${node.action.type.wireName}")
        return PipelineActionResult(success)
    }

    private fun fail(node: PipelineNode, message: String): PipelineActionResult {
        logBus.warn("Pipeline 节点 ${node.name} 动作失败: $message")
        return PipelineActionResult(false)
    }

    private companion object {
        const val SLEEP_SLICE_MS = 20L
    }
}
