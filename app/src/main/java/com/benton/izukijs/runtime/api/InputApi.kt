package com.benton.izukijs.runtime.api

import android.webkit.JavascriptInterface
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.DeviceController
import com.benton.izukijs.controller.GesturePoint
import com.benton.izukijs.controller.GestureStroke
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.runtime.LogBus
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

    @JavascriptInterface
    fun click(x: Double, y: Double): Boolean {
        val controller = gestureController()
        val ok = controller?.click(x.toFloat(), y.toFloat()) ?: false
        logBus.debug("点击 (${x.toInt()}, ${y.toInt()})${controller.resultTag(ok)}")
        return ok
    }

    @JavascriptInterface
    fun longClick(x: Double, y: Double, durationMillis: Double): Boolean {
        val controller = gestureController()
        val ok = controller?.longClick(x.toFloat(), y.toFloat(), durationMillis.toLong()) ?: false
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
        val ok = controller?.swipe(
            x1.toFloat(),
            y1.toFloat(),
            x2.toFloat(),
            y2.toFloat(),
            durationMillis.toLong(),
        ) ?: false
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
     * 无障碍后端支持带时间戳的完整轨迹；Shizuku / Root / HID 无法表达曲线与停顿，
     * 会降级为逐段 [DeviceController.swipe]（同点段等价于长按）。
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
        if (preferred != null && preferred.supportsGesture() && preferred.gesture(strokes)) {
            logBus.debug("$summary${preferred.resultTag(true)}")
            return true
        }
        val capable = gestureCapableController(preferred)
        if (capable != null && capable.gesture(strokes)) {
            logBus.debug("$summary${capable.resultTag(true)}")
            return true
        }
        // 全部失败，才退回能力协商选出的后端做分段近似。
        val fallback = preferred ?: capable ?: run {
            logBus.debug("$summary → 无可用后端 ✗")
            return false
        }
        val ok = gestureFallback(fallback, strokes)
        logBus.debug("$summary（分段近似）${fallback.resultTag(ok)}")
        return ok
    }

    private fun gestureCapableController(exclude: DeviceController?): DeviceController? =
        controllers.all().firstOrNull {
            it !== exclude && !controllers.isDisabled(it.mode) && it.isReady() && it.supportsGesture()
        }

    /** 不支持复杂轨迹的后端：按相邻点逐段滑动，原地不动的段用长按模拟停顿。 */
    private fun gestureFallback(controller: DeviceController, strokes: List<GestureStroke>): Boolean {
        var sent = false
        for (stroke in strokes) {
            val points = stroke.points
            for (i in 0 until points.size - 1) {
                val from = points[i]
                val to = points[i + 1]
                val duration = (to.timeMs - from.timeMs).coerceAtLeast(1L)
                val ok = if (from.x == to.x && from.y == to.y) {
                    controller.longClick(from.x, from.y, duration)
                } else {
                    controller.swipe(from.x, from.y, to.x, to.y, duration)
                }
                sent = sent || ok
            }
        }
        return sent
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
            autoTime = point.timeMs + DEFAULT_STEP_MS
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
        val controller = controllers.controllerFor(Capability.KEY)
        val ok = controller?.pressKey(keyCode) ?: false
        logBus.debug("按键 keyCode=$keyCode${controller.resultTag(ok)}")
        return ok
    }

    @JavascriptInterface
    fun input(text: String): Boolean {
        val controller = controllers.controllerFor(Capability.TEXT)
        val ok = controller?.inputText(text) ?: false
        logBus.debug("输入文本 \"${text.preview()}\"${controller.resultTag(ok)}")
        return ok
    }

    @JavascriptInterface
    fun back(): Boolean = globalAction(GLOBAL_BACK, "返回")

    @JavascriptInterface
    fun home(): Boolean = globalAction(GLOBAL_HOME, "主页")

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
        const val DEFAULT_STEP_MS = 16L

        /** 解析失败时回显原始 JSON 的最大长度。 */
        const val GESTURE_JSON_PREVIEW = 300

        const val GLOBAL_BACK = 1 // AccessibilityService.GLOBAL_ACTION_BACK
        const val GLOBAL_HOME = 2 // AccessibilityService.GLOBAL_ACTION_HOME
        const val GLOBAL_RECENTS = 3 // AccessibilityService.GLOBAL_ACTION_RECENTS
    }
}
