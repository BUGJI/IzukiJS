package com.benton.izukijs.ai

import android.view.KeyEvent
import com.benton.izukijs.controller.NodeSnapshot
import com.benton.izukijs.runtime.api.AppApi
import com.benton.izukijs.runtime.api.DeviceApi
import com.benton.izukijs.runtime.api.InputApi
import com.benton.izukijs.runtime.api.OcrApi
import com.benton.izukijs.runtime.api.SelectorApi
import com.benton.izukijs.runtime.api.ShellApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent 可调用的设备工具集。全部复用脚本层已有的 API 对象，保证行为与脚本一致。
 *
 * 每个工具返回一段人类可读文本，作为 tool 消息回填给模型。
 */
class AgentTools(
    private val inputApi: InputApi,
    private val selectorApi: SelectorApi,
    private val ocrApi: OcrApi,
    private val appApi: AppApi,
    private val shellApi: ShellApi,
    private val deviceApi: DeviceApi,
    private val screenshotPathProvider: () -> String?,
    private val nodeTreeProvider: () -> NodeSnapshot?,
    private val allowShell: Boolean,
) {

    private val shellAvailable: Boolean get() = allowShell && shellApi.available()

    private var cachedShellAvailable: Boolean? = null
    private var cachedSpecs: List<ToolSpec> = emptyList()

    /** 工具 schema 只随 shell 可用性变化，缓存避免每步都重建整份 JSON。 */
    fun specs(): List<ToolSpec> {
        val shell = shellAvailable
        if (cachedShellAvailable == shell) return cachedSpecs
        return buildSpecs(shell).also {
            cachedShellAvailable = shell
            cachedSpecs = it
        }
    }

    private fun buildSpecs(shellAvailable: Boolean): List<ToolSpec> = buildList {
        add(
            ToolSpec(
                "ocr_screen",
                "识别当前屏幕的全部文字并返回。用于了解当前界面内容，是最常用的观察手段。",
                objectSchema(emptyMap()),
            ),
        )
        add(
            ToolSpec(
                "ui_dump",
                "读取当前界面的可点击控件树（含文本、控件 id 与屏幕坐标）。当 OCR 不够精确时使用。",
                objectSchema(emptyMap()),
            ),
        )
        add(
            ToolSpec(
                "find_text",
                "在当前屏幕上查找包含指定文字的元素，返回其中心坐标。",
                objectSchema(mapOf("text" to stringProp("要查找的文字")), required = listOf("text")),
            ),
        )
        add(
            ToolSpec(
                "click",
                "点击屏幕上的绝对坐标。",
                objectSchema(
                    mapOf(
                        "x" to numberProp("横坐标（像素）"),
                        "y" to numberProp("纵坐标（像素）"),
                    ),
                    required = listOf("x", "y"),
                ),
            ),
        )
        add(
            ToolSpec(
                "long_click",
                "长按屏幕坐标。",
                objectSchema(
                    mapOf(
                        "x" to numberProp("横坐标（像素）"),
                        "y" to numberProp("纵坐标（像素）"),
                        "duration_ms" to numberProp("按住时长（毫秒）"),
                    ),
                    required = listOf("x", "y"),
                ),
            ),
        )
        add(
            ToolSpec(
                "swipe",
                "从一点滑动到另一点，可用于滚动列表。",
                objectSchema(
                    mapOf(
                        "x1" to numberProp("起点横坐标"),
                        "y1" to numberProp("起点纵坐标"),
                        "x2" to numberProp("终点横坐标"),
                        "y2" to numberProp("终点纵坐标"),
                        "duration_ms" to numberProp("滑动时长（毫秒）"),
                    ),
                    required = listOf("x1", "y1", "x2", "y2"),
                ),
            ),
        )
        add(
            ToolSpec(
                "input_text",
                "向当前焦点输入框输入文本。",
                objectSchema(mapOf("text" to stringProp("要输入的文本")), required = listOf("text")),
            ),
        )
        add(ToolSpec("press_back", "按下返回键。", objectSchema(emptyMap())))
        add(ToolSpec("press_home", "回到桌面。", objectSchema(emptyMap())))
        add(ToolSpec("press_recents", "打开最近任务。", objectSchema(emptyMap())))
        add(ToolSpec("press_enter", "按下回车键。", objectSchema(emptyMap())))
        add(
            ToolSpec(
                "launch_app",
                "根据包名启动应用。",
                objectSchema(
                    mapOf("package" to stringProp("应用包名，如 com.android.settings")),
                    required = listOf("package"),
                ),
            ),
        )
        add(ToolSpec("current_app", "获取当前前台应用的包名。", objectSchema(emptyMap())))
        add(ToolSpec("device_info", "获取设备屏幕尺寸、机型与 Android 版本。", objectSchema(emptyMap())))
        add(
            ToolSpec(
                "screenshot",
                "保存一张当前屏幕截图到文件，返回文件路径。",
                objectSchema(emptyMap()),
            ),
        )
        if (shellAvailable) {
            add(
                ToolSpec(
                    "shell",
                    "执行 Shell 命令并返回输出（需要 Shizuku 或 Root）。",
                    objectSchema(mapOf("command" to stringProp("要执行的命令")), required = listOf("command")),
                ),
            )
        }
    }

    /** 执行一个工具调用，返回结果文本（内部异常会被捕获为错误提示）。 */
    fun execute(name: String, argumentsJson: String): String = runCatching {
        val args = JSONObject(argumentsJson.ifBlank { "{}" })
        dispatch(name, args)
    }.getOrElse { "[错误] ${it.message}" }

    private fun dispatch(name: String, args: JSONObject): String = when (name) {
        "ocr_screen" -> ocrApi.recognize()?.takeIf { it.isNotBlank() }
            ?: "（未识别到文字，或截图权限不可用）"

        "ui_dump" -> formatNodeTree(nodeTreeProvider())

        "find_text" -> {
            val query = args.optString("text")
            val raw = ocrApi.findRaw(query)
            if (raw == null) "未找到包含「$query」的元素" else "找到「$query」中心坐标：${raw.split(",").take(2).joinToString(",")}"
        }

        "click" -> ok(inputApi.click(args.optDouble("x"), args.optDouble("y")), "点击")

        "long_click" -> {
            val duration = args.optDouble("duration_ms", 600.0)
            ok(inputApi.longClick(args.optDouble("x"), args.optDouble("y"), duration), "长按")
        }

        "swipe" -> ok(
            inputApi.swipe(
                args.optDouble("x1"),
                args.optDouble("y1"),
                args.optDouble("x2"),
                args.optDouble("y2"),
                args.optDouble("duration_ms", 300.0),
            ),
            "滑动",
        )

        "input_text" -> ok(inputApi.input(args.optString("text")), "输入文本")

        "press_back" -> ok(inputApi.back(), "返回")

        "press_home" -> ok(inputApi.home(), "回桌面")

        "press_recents" -> ok(inputApi.recents(), "最近任务")

        "press_enter" -> ok(inputApi.press(KeyEvent.KEYCODE_ENTER), "回车")

        "launch_app" -> ok(appApi.launch(args.optString("package")), "启动应用")

        "current_app" -> appApi.currentPackage()?.let { "当前前台包名：$it" } ?: "无法获取当前包名（需无障碍）"

        "device_info" -> "屏幕 ${deviceApi.width()}x${deviceApi.height()}，" +
            "机型 ${deviceApi.brand()} ${deviceApi.model()}，Android SDK ${deviceApi.androidVersion()}"

        "screenshot" -> screenshotPathProvider()?.let { "截图已保存：$it" } ?: "截图失败"

        "shell" -> if (shellAvailable) {
            shellApi.exec(args.optString("command"))?.takeIf { it.isNotBlank() } ?: "（命令无输出）"
        } else {
            "Shell 工具未启用或当前无 Shizuku/Root 后端"
        }

        else -> "[错误] 未知工具：$name"
    }

    private fun ok(success: Boolean, action: String): String =
        if (success) "$action 成功" else "$action 失败（当前控制模式不支持或坐标无效）"

    private fun formatNodeTree(root: NodeSnapshot?): String {
        root ?: return "（无法读取控件树，需开启无障碍）"
        val out = StringBuilder()
        appendNode(root, 0, out)
        return if (out.isEmpty()) "（控件树为空）" else out.toString().trimEnd()
    }

    private fun appendNode(node: NodeSnapshot, depth: Int, out: StringBuilder) {
        if (out.length > MAX_DUMP_CHARS) return
        val label = buildString {
            node.className?.substringAfterLast('.')?.let { append(it) }
            node.viewId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { append("#").append(it) }
            node.text?.takeIf { it.isNotBlank() }?.let { append(" \"").append(it.take(MAX_TEXT_CHARS)).append("\"") }
            node.contentDescription?.takeIf { it.isNotBlank() }
                ?.let { append(" desc=\"").append(it.take(MAX_TEXT_CHARS)).append("\"") }
        }
        if (label.isNotBlank()) {
            out.append("  ".repeat(depth)).append(label)
            if (node.clickable) out.append(" [可点]")
            val cx = (node.left + node.right) / 2
            val cy = (node.top + node.bottom) / 2
            out.append(" @(").append(cx).append(",").append(cy).append(")\n")
        }
        node.children.forEach { appendNode(it, depth + 1, out) }
    }

    private fun objectSchema(
        properties: Map<String, JSONObject>,
        required: List<String> = emptyList(),
    ): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply { properties.forEach { (key, value) -> put(key, value) } })
        if (required.isNotEmpty()) put("required", JSONArray(required))
    }

    private fun stringProp(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    private fun numberProp(description: String): JSONObject =
        JSONObject().put("type", "number").put("description", description)

    private companion object {
        const val MAX_DUMP_CHARS = 4000
        const val MAX_TEXT_CHARS = 48
    }
}
