package com.benton.izukijs.runtime.api

import android.view.KeyEvent
import android.webkit.JavascriptInterface
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.DeviceController
import com.benton.izukijs.controller.GesturePoint
import com.benton.izukijs.controller.GestureStroke
import com.benton.izukijs.controller.TextInputMethod
import com.benton.izukijs.developer.DeveloperTuning
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.service.OverlayCoordinator
import org.json.JSONArray
import org.json.JSONObject

/**
 * 输入 API：click / swipe / gesture / press / back / home / recents / input。
 * 通过能力选择背后的控制模式，脚本层无需关心底层是无障碍、Shizuku 还是 HID。
 *
 * 每个指令都会以 DEBUG 级别写入 [logBus]，便于在「日志」页回溯脚本实际发出的操作。
 */
class InputApi(
    private val controllers: ControllerManager,
    private val logBus: LogBus,
) {

    private fun gestureController(): DeviceController? =
        controllers.controllerFor(Capability.GESTURE)

    /**
     * 触摸注入前先隐藏悬浮层，避免点击落在悬浮条上而非目标应用。
     *
     * 输入打到虚拟屏时不会与主屏悬浮窗重叠，直接执行，避免无谓闪烁。
     */
    private fun <T> withoutOverlayOnMainDisplay(block: () -> T): T =
        if (controllers.targetDisplayId != null) block() else OverlayCoordinator.withoutOverlay(block)

    @JavascriptInterface
    fun click(x: Double, y: Double): Boolean {
        val controller = gestureController()
        val ok = controller?.let {
            withoutOverlayOnMainDisplay { it.click(x.toFloat(), y.toFloat()) }
        } ?: false
        logBus.debug("点击 (${x.toInt()}, ${y.toInt()})${controller.resultTag(ok)}")
        return ok
    }

    @JavascriptInterface
    fun longClick(x: Double, y: Double, durationMillis: Double): Boolean {
        val controller = gestureController()
        val ok = controller?.let {
            withoutOverlayOnMainDisplay {
                it.longClick(x.toFloat(), y.toFloat(), durationMillis.toLong())
            }
        } ?: false
        logBus.debug(
            "长按 (${x.toInt()}, ${y.toInt()}) ${durationMillis.toLong()}ms${controller.resultTag(ok)}",
        )
        return ok
    }

    @JavascriptInterface
    fun swipe(
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        durationMillis: Double,
    ): Boolean {
        val controller = gestureController()
        val ok = controller?.let {
            withoutOverlayOnMainDisplay {
                it.swipe(
                    x1.toFloat(),
                    y1.toFloat(),
                    x2.toFloat(),
                    y2.toFloat(),
                    durationMillis.toLong(),
                )
            }
        } ?: false
        logBus.debug(
            "滑动 (${x1.toInt()}, ${y1.toInt()})→(${x2.toInt()}, ${y2.toInt()}) " +
                "${durationMillis.toLong()}ms${controller.resultTag(ok)}",
        )
        return ok
    }

    /**
     * 复杂手势轨迹（曲线 / 多段 / 多指 / 按住停顿）。
     *
     * QuickJS 无法直接传对象数组，prelude 中把 JS 数组 `JSON.stringify` 后经 [gestureRaw] 传入。
     * 无障碍与 v2 及以上固件的蓝牙 HID 支持带时间戳的完整轨迹；Shizuku / Root 无法表达
     * 曲线与中间点，会降级执行：每个笔画的连续移动段合并为一次 [DeviceController.swipe]，
     * 同点段按点击 / 长按下发。降级会丢失轨迹信息时用 warn 提示。
     */
    @JavascriptInterface
    fun gestureRaw(strokesJson: String): Boolean {
        val strokes = parseStrokes(strokesJson)
        if (strokes == null) {
            logBus.warn("gesture 解析失败，原始数据：${strokesJson.preview(GESTURE_JSON_PREVIEW)}")
            return false
        }
        if (strokes.isEmpty()) {
            logBus.warn("gesture 无有效轨迹点，原始数据：${strokesJson.preview(GESTURE_JSON_PREVIEW)}")
            return false
        }
        val summary = "手势 ${strokes.size} 段 / ${strokes.sumOf { it.points.size }} 点" +
            " [起点 ${strokes.first().points.first().let { "(${it.x.toInt()}, ${it.y.toInt()})" }}]"
        // 先尊重能力协商结果（例如把「手势」固定到 HID / 无障碍）；只要它支持复杂
        // 轨迹就直接执行。否则找其它支持复杂轨迹的后端兜底，避免曲线与停顿被降级
        // 成分段直线而失真。
        val preferred = gestureController()
        val capable = gestureCapableController(preferred)
        val executed = withoutOverlayOnMainDisplay {
            if (preferred != null && preferred.supportsGesture() && preferred.gesture(strokes)) {
                logBus.debug("$summary${preferred.resultTag(true)}")
                true
            } else if (capable != null && capable.gesture(strokes)) {
                logBus.debug("$summary${capable.resultTag(true)}")
                true
            } else {
                false
            }
        }
        if (executed) return true
        // 全部失败，才退回能力协商选出的后端做降级执行。
        val fallback = preferred ?: capable ?: run {
            logBus.debug("$summary → 无可用后端 ✗")
            return false
        }
        val result = withoutOverlayOnMainDisplay { applyGestureFallback(fallback, strokes) }
        val operations = "滑动 ${result.swipes} 次 / 点击 ${result.taps} 次 / 长按 ${result.holds} 次"
        if (result.droppedPoints > 0 || strokes.size > 1) {
            logBus.warn(
                "$summary 降级执行：${strokes.sumOf { it.points.size }} 个点变为 $operations，" +
                    "中间点 / 曲线 / 多指同时性已丢失（该后端无法表达）；" +
                    "只需一次直线滑动请直接用 swipe()${fallback.resultTag(result.ok)}",
            )
        } else {
            logBus.debug("$summary 降级执行：$operations${fallback.resultTag(result.ok)}")
        }
        return result.ok
    }

    private fun gestureCapableController(exclude: DeviceController?): DeviceController? {
        val targetDisplay = controllers.targetDisplayId
        return controllers.all().firstOrNull {
            it !== exclude &&
                !controllers.isDisabled(it.mode) &&
                it.isReady() &&
                it.supportsGesture() &&
                (targetDisplay == null || it.supportsDisplayTarget())
        }
    }

    // ---- 轨迹解析 ----

    private fun parseStrokes(json: String): List<GestureStroke>? = runCatching {
        val root = JSONArray(json)
        if (root.length() == 0) return emptyList()
        if (isPoint(root.opt(0))) {
            // 单指单笔画简写：直接传点数组
            listOfNotNull(parseStroke(root))
        } else {
            (0 until root.length()).mapNotNull { i ->
                (root.opt(i) as? JSONArray)?.let { parseStroke(it) }
            }
        }
    }.getOrNull()

    private fun parseStroke(array: JSONArray): GestureStroke? {
        val points = ArrayList<GesturePoint>(array.length())
        var autoTime = 0L
        for (i in 0 until array.length()) {
            val point = parsePoint(array.opt(i), autoTime) ?: continue
            points.add(point)
            autoTime = point.timeMs + DeveloperTuning.current.inputDefaultStepMs.toLong()
        }
        return if (points.isEmpty()) null else GestureStroke(points)
    }

    private fun parsePoint(value: Any?, autoTime: Long): GesturePoint? {
        val xRaw: Any?
        val yRaw: Any?
        val timeRaw: Any?
        when (value) {
            is JSONObject -> {
                if (!value.has("x") || !value.has("y")) {
                    logBus.warn("gesture 点缺少 x/y：$value")
                    return null
                }
                xRaw = value.opt("x")
                yRaw = value.opt("y")
                timeRaw = when {
                    value.has("t") -> value.opt("t")
                    value.has("timeMs") -> value.opt("timeMs")
                    else -> null
                }
            }
            is JSONArray -> {
                if (value.length() < 2) {
                    logBus.warn("gesture 点数组元素不足：$value")
                    return null
                }
                xRaw = value.opt(0)
                yRaw = value.opt(1)
                timeRaw = if (value.length() >= 3) value.opt(2) else null
            }
            else -> {
                logBus.warn("gesture 点格式无法识别：$value")
                return null
            }
        }
        // JSON.stringify 会把 undefined / NaN 写成 null；此时坐标必须是 null 而不是被静默当成 0。
        val x = xRaw.toCoordinate()
        val y = yRaw.toCoordinate()
        if (x == null || y == null) {
            logBus.warn(
                "gesture 坐标无效（x=${xRaw ?: "null"}, y=${yRaw ?: "null"}）已跳过该点，" +
                    "请检查变量是否未定义或非数字",
            )
            return null
        }
        val explicitTime = timeRaw.toCoordinate()?.toLong()
        return GesturePoint(x.toFloat(), y.toFloat(), explicitTime ?: autoTime)
    }

    /** 数字或可解析为数字的字符串 → Double；null / NaN / Infinity / 其它 → null。 */
    private fun Any?.toCoordinate(): Double? {
        val value = when (this) {
            is Number -> toDouble()
            is String -> toDoubleOrNull()
            else -> null
        }
        return value?.takeIf { it.isFinite() }
    }

    /** 点可写成 `{x,y,t}` 或 `[x,y,t]`；其余形态视为「笔画」。 */
    private fun isPoint(value: Any?): Boolean = when (value) {
        is JSONObject -> value.has("x") && value.has("y")
        is JSONArray -> value.length() >= 2 &&
            value.opt(0) is Number &&
            value.opt(1) is Number
        else -> false
    }

    @JavascriptInterface
    fun press(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_HOME && controllers.targetDisplayId != null) {
            logBus.warn("虚拟屏没有 launcher，home 键不会生效；请改用 back 或 app.launch")
            return false
        }
        val controller = controllers.controllerFor(Capability.KEY)
        val ok = controller?.pressKey(keyCode) ?: false
        logBus.debug("按键 keyCode=$keyCode${controller.resultTag(ok)}")
        return ok
    }

    @JavascriptInterface
    fun input(text: String): Boolean = input(text, TextInputMethod.AUTO)

    /** 指定输入方式：auto / input / clipboard / broadcast。 */
    @JavascriptInterface
    fun inputWithMethod(text: String, method: String): Boolean =
        input(text, TextInputMethod.from(method))

    private fun input(text: String, method: TextInputMethod): Boolean {
        val controller = controllers.controllerFor(Capability.TEXT)
        val ok = controller?.inputText(text, method) ?: false
        logBus.debug("输入文本 \"${text.preview()}\" [${method.name.lowercase()}]${controller.resultTag(ok)}")
        return ok
    }

    @JavascriptInterface
    fun back(): Boolean = globalAction(GLOBAL_BACK, "返回")

    @JavascriptInterface
    fun home(): Boolean {
        if (controllers.targetDisplayId != null) {
            logBus.warn("虚拟屏没有 launcher，home 键不会生效；请改用 back 或 app.launch")
            return false
        }
        return globalAction(GLOBAL_HOME, "主页")
    }

    @JavascriptInterface
    fun recents(): Boolean = globalAction(GLOBAL_RECENTS, "最近任务")

    private fun globalAction(action: Int, label: String): Boolean {
        val controller = controllers.controllerFor(Capability.GLOBAL_ACTION)
        val ok = controller?.globalAction(action) ?: false
        logBus.debug("全局动作 $label${controller.resultTag(ok)}")
        return ok
    }

    private fun DeviceController?.resultTag(ok: Boolean): String {
        val mode: ControlMode? = this?.mode
        val target = mode?.displayName ?: "无可用后端"
        return " → $target ${if (ok) "✓" else "✗"}"
    }

    private fun String.preview(max: Int = 80): String =
        if (length <= max) this else take(max) + "…"

    private companion object {
        /** 解析失败时回显原始 JSON 的最大长度。 */
        const val GESTURE_JSON_PREVIEW = 300

        const val GLOBAL_BACK = 1 // AccessibilityService.GLOBAL_ACTION_BACK
        const val GLOBAL_HOME = 2 // AccessibilityService.GLOBAL_ACTION_HOME
        const val GLOBAL_RECENTS = 3 // AccessibilityService.GLOBAL_ACTION_RECENTS
    }
}

/** 降级执行的手势统计，用于日志告知脚本作者轨迹是否被压缩失真。 */
internal data class GestureFallbackResult(
    val ok: Boolean,
    val swipes: Int,
    val taps: Int,
    val holds: Int,
    /** 连续移动段中被丢弃的中间点数量（曲线 / 折返被拉直）。 */
    val droppedPoints: Int,
)

/**
 * 不支持复杂轨迹的后端（Shizuku / Root / 旧固件 HID）的降级执行。
 *
 * - 连续移动段合并为**一次** [DeviceController.swipe]（起点 → 终点，时长求和）：中间点在
 *   这些后端上无法表达，若逐段下发，一次手势会变成 N-1 次独立 swipe，对分页型 UI 就会
 *   一次滑动翻多页。
 * - 同点段且时长不超过点击上限：视为**点击**，下发一段最短~最长点击时长的同点短按压
 *   （用同点 swipe 而非 longClick，才能控制按压时长；直接 longClick 会被后端抬到长按阈值）。
 * - 同点段且时长更长：视为**按住停顿**，走 [DeviceController.longClick]。
 *
 * 抽成顶层函数以便脱离 Android / JSON 做单元测试。
 */
internal fun applyGestureFallback(
    controller: DeviceController,
    strokes: List<GestureStroke>,
): GestureFallbackResult {
    var sent = false
    var swipes = 0
    var taps = 0
    var holds = 0
    var droppedPoints = 0
    val tapMinMs = DeveloperTuning.current.inputTapMinMs.toLong()
    val tapMaxMs = DeveloperTuning.current.inputTapMaxMs.toLong()
    for (stroke in strokes) {
        val points = stroke.points
        var i = 0
        while (i < points.size - 1) {
            val from = points[i]
            val to = points[i + 1]
            if (from.x != to.x || from.y != to.y) {
                var end = to
                var duration = to.timeMs - from.timeMs
                var j = i + 1
                while (j < points.size - 1) {
                    val nextFrom = points[j]
                    val nextTo = points[j + 1]
                    if (nextFrom.x == nextTo.x && nextFrom.y == nextTo.y) break
                    duration += nextTo.timeMs - nextFrom.timeMs
                    end = nextTo
                    j++
                }
                droppedPoints += (j - i - 1).coerceAtLeast(0)
                val ok = controller.swipe(from.x, from.y, end.x, end.y, duration.coerceAtLeast(1L))
                sent = sent || ok
                swipes++
                i = j
            } else {
                val duration = (to.timeMs - from.timeMs).coerceAtLeast(1L)
                val ok = if (duration <= tapMaxMs) {
                    controller.swipe(
                        from.x,
                        from.y,
                        from.x,
                        from.y,
                        duration.coerceIn(tapMinMs, tapMaxMs),
                    ).also { taps++ }
                } else {
                    controller.longClick(from.x, from.y, duration).also { holds++ }
                }
                sent = sent || ok
                i++
            }
        }
    }
    return GestureFallbackResult(sent, swipes, taps, holds, droppedPoints)
}
