package com.benton.izukijs.mcp.tools

import com.benton.izukijs.ai.AgentTools
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.data.ScriptRepository
import com.benton.izukijs.model.Capability
import com.benton.izukijs.runtime.ScriptExecutionManager
import com.benton.izukijs.runtime.api.DeviceApiBundle
import com.benton.izukijs.service.CaptureSettingsRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 工具目录。
 *
 * 读屏 / 控制类工具直接复用 [AgentTools] 的 schema 与执行逻辑，保证与脚本、AI Agent
 * 行为一致；再补齐选择器、按键、手势、脚本等 MCP 专属工具。
 *
 * [allowShell] / [allowScripts] 由当前配置决定；配置变更时会重建本对象（服务随配置重启）。
 */
class McpToolRegistry(
    private val bundle: DeviceApiBundle,
    private val controllers: ControllerManager,
    private val captureSettingsRepository: CaptureSettingsRepository,
    private val scriptRepository: ScriptRepository,
    private val executionManager: ScriptExecutionManager,
    private val allowShell: Boolean,
    private val allowScripts: Boolean,
) {

    private val agentTools = AgentTools(
        inputApi = bundle.inputApi,
        selectorApi = bundle.selectorApi,
        ocrApi = bundle.ocrApi,
        appApi = bundle.appApi,
        shellApi = bundle.shellApi,
        deviceApi = bundle.deviceApi,
        screenshotPathProvider = { null },
        nodeTreeProvider = { controllers.controllerFor(Capability.NODE_TREE)?.nodeTree() },
        allowShell = allowShell,
    )

    private val catalog: List<McpTool> by lazy { buildCatalog() }

    fun tools(): List<McpTool> = catalog

    fun find(name: String): McpTool? = catalog.firstOrNull { it.name == name }

    private fun buildCatalog(): List<McpTool> = buildList {
        addAll(sharedTools())
        add(screenshotTool())
        addAll(selectorTools())
        addAll(inputExtraTools())
        add(batteryTool())
        if (allowScripts) addAll(scriptTools())
    }

    // ---- 复用 AgentTools 的工具 ----

    private fun sharedTools(): List<McpTool> =
        agentTools.specs()
            .filter { it.name != "screenshot" }
            .map { spec ->
                McpTool(
                    name = spec.name,
                    description = spec.description,
                    inputSchema = spec.parameters,
                    readOnly = spec.name in READ_ONLY_SHARED,
                ) { args -> McpToolResult.text(agentTools.execute(spec.name, args.toString())) }
            }

    // ---- 截图（返回图像内容块） ----

    private fun screenshotTool(): McpTool = McpTool(
        name = "screenshot",
        description = "捕获当前屏幕，返回一张 JPEG 图像（Base64）及其像素尺寸。",
        inputSchema = McpSchemas.objectSchema(emptyMap()),
        readOnly = true,
    ) {
        val source = bundle.screenshotProvider()
            ?: return@McpTool McpToolResult.error("截图失败：无可用截图后端（需屏幕捕获 / 无障碍 / Shizuku / Root）")
        val encoded = try {
            ScreenshotEncoder.encode(source, captureSettingsRepository.current())
        } finally {
            source.recycle()
        }
        McpToolResult(
            listOf(
                McpContent.image(encoded.base64, encoded.mimeType),
                McpContent.text("屏幕 ${encoded.width}×${encoded.height}"),
            ),
        )
    }

    // ---- 无障碍选择器 ----

    private fun selectorTools(): List<McpTool> = listOf(
        selectorTool("click_by_id", "点击指定控件 id。", "viewId", "控件 id，如 com.example:id/confirm") {
            bundle.selectorApi.clickById(it.getString("viewId"))
        },
        selectorTool("click_by_text", "点击文本匹配的控件。", "text", "控件文本") {
            bundle.selectorApi.clickByText(it.getString("text"))
        },
        selectorTool("click_by_desc", "点击内容描述匹配的控件。", "desc", "内容描述") {
            bundle.selectorApi.clickByDesc(it.getString("desc"))
        },
        selectorTool("long_click_by_id", "长按指定控件 id。", "viewId", "控件 id") {
            bundle.selectorApi.longClickById(it.getString("viewId"))
        },
        selectorTool("long_click_by_text", "长按文本匹配的控件。", "text", "控件文本") {
            bundle.selectorApi.longClickByText(it.getString("text"))
        },
        McpTool(
            name = "set_text_by_id",
            description = "给指定输入框控件设置文本（需无障碍）。",
            inputSchema = McpSchemas.objectSchema(
                mapOf(
                    "viewId" to McpSchemas.string("控件 id"),
                    "text" to McpSchemas.string("要设置的文本"),
                ),
                required = listOf("viewId", "text"),
            ),
        ) { args ->
            ok(bundle.selectorApi.setTextById(args.getString("viewId"), args.getString("text")), "设置文本")
        },
        McpTool(
            name = "get_text_by_id",
            description = "读取指定控件 id 的文本（需无障碍）。",
            inputSchema = McpSchemas.objectSchema(
                mapOf("viewId" to McpSchemas.string("控件 id")),
                required = listOf("viewId"),
            ),
            readOnly = true,
        ) { args ->
            val text = bundle.selectorApi.getTextById(args.getString("viewId"))
            McpToolResult.text(text ?: "（未找到该控件或无文本）")
        },
        McpTool(
            name = "bounds_by_id",
            description = "读取指定控件 id 在屏幕上的边界，返回 left,top,right,bottom（需无障碍）。",
            inputSchema = McpSchemas.objectSchema(
                mapOf("viewId" to McpSchemas.string("控件 id")),
                required = listOf("viewId"),
            ),
            readOnly = true,
        ) { args ->
            val bounds = bundle.selectorApi.boundsById(args.getString("viewId"))
            McpToolResult.text(bounds ?: "（未找到该控件）")
        },
        McpTool(
            name = "exists_by_id",
            description = "判断指定控件 id 是否存在（需无障碍）。",
            inputSchema = McpSchemas.objectSchema(
                mapOf("viewId" to McpSchemas.string("控件 id")),
                required = listOf("viewId"),
            ),
            readOnly = true,
        ) { args ->
            McpToolResult.text(if (bundle.selectorApi.existsById(args.getString("viewId"))) "存在" else "不存在")
        },
    )

    private fun selectorTool(
        name: String,
        description: String,
        param: String,
        paramDesc: String,
        action: (JSONObject) -> Boolean,
    ): McpTool = McpTool(
        name = name,
        description = "$description（需无障碍）",
        inputSchema = McpSchemas.objectSchema(
            mapOf(param to McpSchemas.string(paramDesc)),
            required = listOf(param),
        ),
    ) { args -> ok(action(args), description) }

    // ---- 按键 / 手势 / 打开链接 ----

    private fun inputExtraTools(): List<McpTool> = listOf(
        McpTool(
            name = "press_key",
            description = "按下物理按键。key_code 为 Android KeyEvent 键码（如返回=4、回车=66、主页=3、音量+ =24）。",
            inputSchema = McpSchemas.objectSchema(
                mapOf("key_code" to McpSchemas.integer("Android KeyEvent 键码")),
                required = listOf("key_code"),
            ),
        ) { args ->
            ok(bundle.inputApi.press(args.getInt("key_code")), "按键 ${args.getInt("key_code")}")
        },
        McpTool(
            name = "gesture",
            description = "注入一条复杂手势轨迹（曲线 / 多段 / 按住停顿）。strokes 为笔画数组，每个点可写 [x,y] 或 [x,y,时间ms]。",
            inputSchema = McpSchemas.objectSchema(
                mapOf("strokes" to McpSchemas.gestureStrokes("手势轨迹，例如 [[[100,200],[300,400,120]]]")),
                required = listOf("strokes"),
            ),
        ) { args ->
            val raw = args.optJSONArray("strokes")?.toString() ?: "[]"
            ok(bundle.inputApi.gestureRaw(raw), "手势")
        },
        McpTool(
            name = "open_url",
            description = "用系统默认方式打开一个链接。",
            inputSchema = McpSchemas.objectSchema(
                mapOf("url" to McpSchemas.string("要打开的 URL")),
                required = listOf("url"),
            ),
        ) { args -> ok(bundle.appApi.openUrl(args.getString("url")), "打开链接") },
    )

    private fun batteryTool(): McpTool = McpTool(
        name = "battery",
        description = "读取当前电量百分比。",
        inputSchema = McpSchemas.objectSchema(emptyMap()),
        readOnly = true,
    ) {
        McpToolResult.text("电量 ${bundle.deviceApi.batteryLevel()}%")
    }

    // ---- 脚本（受 allowScripts 控制） ----

    private fun scriptTools(): List<McpTool> = listOf(
        McpTool(
            name = "list_scripts",
            description = "列出设备上保存的脚本。",
            inputSchema = McpSchemas.objectSchema(emptyMap()),
            readOnly = true,
        ) {
            val array = JSONArray()
            scriptRepository.list().forEach { script ->
                array.put(
                    JSONObject().apply {
                        put("id", script.id)
                        put("name", script.name)
                        put("updatedAt", script.updatedAt)
                    },
                )
            }
            McpToolResult.text(array.toString())
        },
        McpTool(
            name = "read_script",
            description = "读取指定脚本的源代码。",
            inputSchema = McpSchemas.objectSchema(
                mapOf("id" to McpSchemas.string("脚本 id")),
                required = listOf("id"),
            ),
            readOnly = true,
        ) { args ->
            val script = scriptRepository.find(args.getString("id"))
                ?: return@McpTool McpToolResult.error("找不到脚本：${args.getString("id")}")
            McpToolResult.text(scriptRepository.read(script).ifBlank { "（脚本内容为空）" })
        },
        McpTool(
            name = "run_script",
            description = "运行指定脚本。脚本在自己的运行时中执行，本调用立即返回，可用 running_script / stop_script 观察与停止。",
            inputSchema = McpSchemas.objectSchema(
                mapOf(
                    "id" to McpSchemas.string("脚本 id"),
                    "values" to JSONObject()
                        .put("type", "object")
                        .put("description", "可选的运行参数（字符串键值对）")
                        .put("additionalProperties", JSONObject().put("type", "string")),
                ),
                required = listOf("id"),
            ),
        ) { args ->
            val script = scriptRepository.find(args.getString("id"))
                ?: return@McpTool McpToolResult.error("找不到脚本：${args.getString("id")}")
            if (executionManager.running.value) {
                return@McpTool McpToolResult.error("已有脚本正在运行：${executionManager.runningScript.value}")
            }
            val values = args.optJSONObject("values")?.let { obj ->
                obj.keys().asSequence().associateWith { obj.optString(it) }
            } ?: emptyMap()
            executionManager.run(script.name, scriptRepository.read(script), values)
            McpToolResult.text("已开始运行脚本：${script.name}")
        },
        McpTool(
            name = "stop_script",
            description = "请求停止当前正在运行的脚本。",
            inputSchema = McpSchemas.objectSchema(emptyMap()),
        ) {
            executionManager.requestStop()
            McpToolResult.text("已请求停止脚本")
        },
        McpTool(
            name = "running_script",
            description = "返回当前正在运行的脚本名，没有则返回空。",
            inputSchema = McpSchemas.objectSchema(emptyMap()),
            readOnly = true,
        ) {
            val running = executionManager.runningScript.value
            McpToolResult.text(running ?: "（当前没有运行中的脚本）")
        },
    )

    private fun ok(success: Boolean, action: String): McpToolResult =
        if (success) {
            McpToolResult.text("$action 成功")
        } else {
            McpToolResult.error("$action 失败（当前控制模式不支持或目标无效）")
        }

    private companion object {
        val READ_ONLY_SHARED = setOf(
            "ocr_screen",
            "ui_dump",
            "find_text",
            "current_app",
            "device_info",
        )
    }
}
