package com.benton.izukijs.mcp.tools

import android.view.KeyEvent
import com.benton.izukijs.mcp.core.McpConfig
import com.benton.izukijs.virtualscreen.ScreenTarget
import com.benton.izukijs.virtualscreen.VirtualScreenManager
import com.benton.izukijs.virtualscreen.VirtualScreenOptions
import com.benton.izukijs.virtualscreen.VirtualScreenResolution
import com.benton.izukijs.virtualscreen.VirtualScreenState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

// ---- 后台虚拟屏 ----

internal fun McpToolRegistry.virtualScreenTool(
    manager: VirtualScreenManager,
    config: McpConfig,
): McpTool = McpTool(
    name = "virtual_screen",
    description = "后台虚拟屏：start / stop / status / capture / launch。每次调用自带 target，" +
        "不会改变脚本的全局操作目标。capture 返回图像内容块；虚拟屏不支持 ui_dump / " +
        "find(source=ui)，请改用 capture / ocr / 坐标输入。",
    inputSchema = McpSchemas.objectSchema(
        mapOf(
            "action" to McpSchemas.enum(
                "操作",
                listOf("start", "stop", "status", "capture", "launch"),
            ),
            "width" to McpSchemas.integer("start 时的宽度，默认取设置"),
            "height" to McpSchemas.integer("start 时的高度，默认取设置"),
            "dpi" to McpSchemas.integer("start 时的密度，默认取设置"),
            "package" to McpSchemas.string("launch 时的应用包名"),
            "scale" to McpSchemas.number("capture 缩放比例 0.1~1，默认 1"),
            "quality" to McpSchemas.integer("capture 压缩质量 1~100，默认 90"),
            "region" to McpSchemas.intArray("capture 裁剪区域 [l,t,r,b]（虚拟屏像素）"),
            "format" to McpSchemas.enum("capture 图像格式", listOf("jpeg", "webp")),
            "wait_next_frame" to McpSchemas.boolean(
                "先等待比 after_frame 更新的帧再截图；after_frame 省略时等待当前帧之后的下一帧",
            ),
            "after_frame" to McpSchemas.integer("wait_next_frame 的基准 frameSeq，通常传上一条输入响应里的 frameSeq"),
            "frame_timeout_ms" to McpSchemas.integer("wait_next_frame 的超时（毫秒），默认 1000"),
        ),
        required = listOf("action"),
    ),
) { args ->
    when (val action = args.optString("action", "status")) {
        "start" -> {
            val base = manager.configuredResolution()
            val options = VirtualScreenOptions(
                VirtualScreenResolution(
                    width = args.optInt("width", base.width),
                    height = args.optInt("height", base.height),
                    dpi = args.optInt("dpi", base.dpi),
                ).normalized(),
            )
            val ok = manager.startBlocking(options)
            val payload = virtualScreenStateJson(manager)
                .put("ok", ok)
                .put("retryable", !ok)
            // 启动失败必须走 error：过去用普通文本返回 state=idle，客户端会把失败当成功。
            if (ok) {
                McpToolResult.text(payload.toString())
            } else {
                McpToolResult.error(payload.toString())
            }
        }

        "stop" -> {
            manager.stopBlocking()
            McpToolResult.text(
                virtualScreenStateJson(manager).put("ok", true).toString(),
            )
        }

        "status" -> McpToolResult.text(virtualScreenStateJson(manager).toString())

        "capture" -> {
            if (!manager.isActive()) {
                return@McpTool McpToolResult.error("虚拟屏未运行，请先执行 action=start")
            }
            if (args.optBoolean("wait_next_frame", false)) {
                val baseline = if (args.has("after_frame")) {
                    args.optLong("after_frame", 0L)
                } else {
                    manager.frameSeq()
                }
                val timeout = args.optInt("frame_timeout_ms", DEFAULT_CAPTURE_FRAME_WAIT_MS)
                    .coerceIn(0, MAX_CAPTURE_FRAME_WAIT_MS)
                    .toLong()
                manager.awaitFrameAfter(baseline, timeout)
            }
            val source = bundle.captureProvider(ScreenTarget.VIRTUAL, CAPTURE_WAIT_MS)
                ?: return@McpTool McpToolResult.error(
                    "虚拟屏已启动，但尚未收到画面帧；请先用 action=launch 启动一个应用，" +
                        "或等待首帧绘制后重试",
                )
            encodeCapture(source, args)
        }

        "launch" -> {
            val packageName = args.optString("package").trim()
            if (packageName.isEmpty()) {
                return@McpTool McpToolResult.error("action=launch 需要 package")
            }
            if (!manager.isActive()) {
                return@McpTool McpToolResult.error("虚拟屏未运行，请先执行 action=start")
            }
            if (config.isPackageBlocked(packageName)) {
                return@McpTool blocked("目标应用 $packageName 在敏感应用黑名单中，已拒绝启动")
            }
            val ok = manager.launchBlocking(packageName)
            McpToolResult.text(
                JSONObject()
                    .put("ok", ok)
                    .put("package", packageName)
                    .put("displayId", manager.displayId())
                    .toString(),
            )
        }

        else -> McpToolResult.error("未知 action：$action")
    }
}

internal fun McpToolRegistry.virtualScreenInputTool(manager: VirtualScreenManager): McpTool = McpTool(
    name = "virtual_screen_input",
    description = "在后台虚拟屏上输入。坐标为相对虚拟屏的 0~1 归一化值；不会改变脚本的" +
        "全局操作目标。输入成功后默认等待画面重绘（wait_frame=true）。action=key 时 key 可为 " +
        "back / recents / enter / esc 或键码；虚拟屏没有 launcher，home 会返回明确错误。",
    inputSchema = McpSchemas.objectSchema(
        mapOf(
            "action" to McpSchemas.enum(
                "操作",
                listOf("tap", "long_press", "swipe", "key"),
            ),
            "x" to McpSchemas.number("tap / long_press 的归一化 x（0~1）"),
            "y" to McpSchemas.number("tap / long_press 的归一化 y（0~1）"),
            "x1" to McpSchemas.number("swipe 起点归一化 x（0~1）"),
            "y1" to McpSchemas.number("swipe 起点归一化 y（0~1）"),
            "x2" to McpSchemas.number("swipe 终点归一化 x（0~1）"),
            "y2" to McpSchemas.number("swipe 终点归一化 y（0~1）"),
            "duration_ms" to McpSchemas.integer("长按 / 滑动时长（毫秒），默认 600 / 300"),
            "key" to McpSchemas.string("按键：back / home / recents / enter / esc 或键码"),
            "wait_frame" to McpSchemas.boolean("输入成功后等待画面重绘再返回，默认 true"),
            "frame_timeout_ms" to McpSchemas.integer("等待新帧的超时（毫秒），默认 600"),
        ),
        required = listOf("action"),
    ),
) { args ->
    if (!manager.isActive()) {
        return@McpTool McpToolResult.error("虚拟屏未运行，请先执行 virtual_screen action=start")
    }
    val size = manager.size()
        ?: return@McpTool McpToolResult.error("虚拟屏分辨率不可用")
    val waitFrame = args.optBoolean("wait_frame", true)
    val frameTimeoutMs = args.optInt("frame_timeout_ms", DEFAULT_INPUT_FRAME_WAIT_MS)
        .coerceIn(0, MAX_INPUT_FRAME_WAIT_MS)
        .toLong()

    /** 输入成功后等待画面重绘，响应里带上新的 frameSeq，避免调用方立刻截图拿到上一帧。 */
    fun finishInput(
        action: String,
        ok: Boolean,
        baseline: Long,
        build: JSONObject.() -> Unit,
    ): McpToolResult {
        val advanced = if (ok && waitFrame) manager.awaitFrameAfter(baseline, frameTimeoutMs) else false
        return McpToolResult.text(
            JSONObject().apply {
                put("ok", ok)
                put("action", action)
                put("frameSeq", manager.frameSeq())
                put("frameAdvanced", advanced)
                if (ok && waitFrame && !advanced) put("frameWaitMs", frameTimeoutMs)
                build()
            }.toString(),
        )
    }

    when (val action = args.optString("action")) {
        "tap", "long_press" -> {
            val x = normalizedCoordinate(args, "x")
            val y = normalizedCoordinate(args, "y")
            if (x == null || y == null) {
                return@McpTool McpToolResult.error("$action 需要 0~1 的 x / y")
            }
            val px = (x * size.width).toFloat()
            val py = (y * size.height).toFloat()
            val duration = args.optInt("duration_ms", if (action == "tap") 300 else 600)
            val baseline = manager.frameSeq()
            val ok = runCatching {
                runBlocking {
                    withTimeout(INPUT_TIMEOUT_MS) {
                        if (action == "tap") {
                            manager.tap(px, py)
                        } else {
                            manager.longPress(px, py, duration.toLong())
                        }
                    }
                }
            }.getOrDefault(false)
            finishInput(action, ok, baseline) {
                put("x", px.toInt())
                put("y", py.toInt())
            }
        }

        "swipe" -> {
            val x1 = normalizedCoordinate(args, "x1")
            val y1 = normalizedCoordinate(args, "y1")
            val x2 = normalizedCoordinate(args, "x2")
            val y2 = normalizedCoordinate(args, "y2")
            if (x1 == null || y1 == null || x2 == null || y2 == null) {
                return@McpTool McpToolResult.error("swipe 需要 0~1 的 x1 / y1 / x2 / y2")
            }
            val duration = args.optInt("duration_ms", 300)
            val baseline = manager.frameSeq()
            val ok = runCatching {
                runBlocking {
                    withTimeout(INPUT_TIMEOUT_MS) {
                        manager.swipe(
                            (x1 * size.width).toFloat(),
                            (y1 * size.height).toFloat(),
                            (x2 * size.width).toFloat(),
                            (y2 * size.height).toFloat(),
                            duration.toLong(),
                        )
                    }
                }
            }.getOrDefault(false)
            finishInput("swipe", ok, baseline) {
                put("duration_ms", duration)
            }
        }

        "key" -> {
            val keyCode = keyCode(args.optString("key"))
                ?: return@McpTool McpToolResult.error("无法识别的按键：${args.optString("key")}")
            if (keyCode == KeyEvent.KEYCODE_HOME) {
                return@McpTool McpToolResult.error(
                    "虚拟屏没有 launcher，home 键不会生效；请改用 back，" +
                        "或用 virtual_screen action=launch 启动桌面应用",
                )
            }
            val baseline = manager.frameSeq()
            val ok = runCatching {
                runBlocking { withTimeout(INPUT_TIMEOUT_MS) { manager.key(keyCode) } }
            }.getOrDefault(false)
            finishInput("key", ok, baseline) {
                put("key_code", keyCode)
            }
        }

        else -> McpToolResult.error("未知 action：$action")
    }
}

private fun McpToolRegistry.virtualScreenStateJson(manager: VirtualScreenManager): JSONObject {
    val state = manager.state.value
    val resolution = manager.size() ?: manager.configuredResolution()
    return JSONObject().apply {
        put("available", manager.available())
        put("active", manager.isActive())
        put("displayId", manager.displayId())
        put("target", manager.target().displayName())
        put("resolvedTarget", manager.resolvedTarget().displayName())
        put("frameSeq", manager.frameSeq())
        put("canCapture", manager.canCapture())
        put("width", resolution.width)
        put("height", resolution.height)
        put("dpi", resolution.dpi)
        manager.lastStopReason()?.let { put("lastStopReason", it) }
        if (manager.lastStopAt() > 0L) put("lastStopAt", manager.lastStopAt())
        put("state", when (state) {
            VirtualScreenState.Idle -> "idle"
            VirtualScreenState.Starting -> "starting"
            is VirtualScreenState.Active -> "active"
            is VirtualScreenState.Failed -> "failed"
        })
        if (state is VirtualScreenState.Failed) put("message", state.message)
    }
}

private fun McpToolRegistry.normalizedCoordinate(args: JSONObject, name: String): Double? {
    if (!args.has(name)) return null
    val value = args.optDouble(name, Double.NaN)
    return value.takeIf { it.isFinite() && it in 0.0..1.0 }
}

private fun McpToolRegistry.keyCode(raw: String): Int? = when (raw.trim().lowercase()) {
    "back" -> KeyEvent.KEYCODE_BACK
    "home" -> KeyEvent.KEYCODE_HOME
    "recents", "recent", "app_switch" -> KeyEvent.KEYCODE_APP_SWITCH
    "enter" -> KeyEvent.KEYCODE_ENTER
    "esc", "escape" -> KeyEvent.KEYCODE_ESCAPE
    else -> raw.trim().toIntOrNull()
}
