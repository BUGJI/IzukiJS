package com.benton.izukijs.mcp.tools

import android.graphics.Rect
import com.benton.izukijs.ai.AgentTools
import com.benton.izukijs.ai.ShellGuard
import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.data.ScriptRepository
import com.benton.izukijs.mcp.McpConfig
import com.benton.izukijs.runtime.LogFileStore
import com.benton.izukijs.runtime.ScriptExecutionManager
import com.benton.izukijs.runtime.api.DeviceApiBundle
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 工具目录。
 *
 * 读屏 / 控制类工具直接复用 [AgentTools] 的 schema 与执行逻辑，保证与脚本、AI Agent
 * 行为一致；这里只额外提供 MCP 专属的截图（图像内容块）、异步结果查询与脚本管理。
 *
 * 目录按 [configProvider] 的当前配置惰性重建：`allowShell` / `allowScripts` /
 * `allowDangerousShell` / 黑名单变更后，下一次 `tools/list` 即反映新配置，
 * 无需重建监听（也就不会打断在线会话）。
 */
class McpToolRegistry(
    private val bundle: DeviceApiBundle,
    private val nodeTreeProvider: () -> NodeTreeResult,
    private val scriptRepository: ScriptRepository,
    private val executionManager: ScriptExecutionManager,
    private val operations: OperationRegistry,
    private val logFileStore: LogFileStore,
    private val configProvider: () -> McpConfig,
) {

    @Volatile
    private var cachedConfig: McpConfig? = null

    @Volatile
    private var cachedTools: List<McpTool> = emptyList()

    fun tools(): List<McpTool> {
        val config = configProvider()
        if (config != cachedConfig) {
            synchronized(this) {
                if (config != cachedConfig) {
                    cachedTools = buildCatalog(config)
                    cachedConfig = config
                }
            }
        }
        return cachedTools
    }

    fun find(name: String): McpTool? = tools().firstOrNull { it.name == name }

    private fun buildCatalog(config: McpConfig): List<McpTool> {
        val agentTools = AgentTools(
            inputApi = bundle.inputApi,
            selectorApi = bundle.selectorApi,
            ocrApi = bundle.ocrApi,
            appApi = bundle.appApi,
            shellApi = bundle.shellApi,
            deviceApi = bundle.deviceApi,
            notificationApi = bundle.notificationApi,
            screenshotPathProvider = { null },
            nodeTreeProvider = nodeTreeProvider,
            allowShell = config.allowShell,
        )
        return buildList {
            addAll(sharedTools(agentTools, config))
            add(screenshotTool())
            add(readLogsTool())
            add(getResultTool())
            add(listOperationsTool())
            if (config.allowScripts) addAll(scriptTools())
        }
    }

    // ---- 复用 AgentTools 的工具 ----

    private fun sharedTools(agentTools: AgentTools, config: McpConfig): List<McpTool> =
        agentTools.specs()
            .filter { it.name != "screenshot" }
            .map { spec ->
                McpTool(
                    name = spec.name,
                    description = spec.description,
                    inputSchema = spec.parameters,
                    readOnly = spec.name in READ_ONLY_SHARED,
                ) { args -> runShared(agentTools, config, spec.name, args) }
            }

    /** 共用工具的执行入口，叠加安全护栏（危险 shell / 敏感 App 启动）。 */
    private fun runShared(
        agentTools: AgentTools,
        config: McpConfig,
        name: String,
        args: JSONObject,
    ): McpToolResult {
        when (name) {
            "shell" -> {
                if (!config.allowDangerousShell) {
                    val reason = ShellGuard.reason(args.optString("command"))
                    if (reason != null) return blocked("命令被安全策略拦截（$reason）")
                }
            }

            "app" -> {
                if (args.optString("action") == "launch") {
                    val pkg = args.optString("package")
                    if (pkg.isNotBlank() && config.isPackageBlocked(pkg)) {
                        return blocked("目标应用 $pkg 在敏感应用黑名单中，已拒绝启动")
                    }
                }
            }
        }
        return McpToolResult.text(agentTools.execute(name, args.toString()))
    }

    private fun blocked(message: String): McpToolResult = McpToolResult.text(
        JSONObject().apply {
            put("ok", false)
            put("blocked", true)
            put("message", message)
        }.toString(),
    )

    // ---- 日志 ----

    private fun readLogsTool(): McpTool = McpTool(
        name = "read_logs",
        description = "读取应用最近日志的尾部（按行数限制），用于排查脚本运行 / 控制后端问题。",
        inputSchema = McpSchemas.objectSchema(
            mapOf("lines" to McpSchemas.integer("返回行数 1~2000，默认 200")),
        ),
        readOnly = true,
        synchronous = true,
    ) { args ->
        McpToolResult.text(logFileStore.tail(args.optInt("lines", 200).coerceIn(1, 2000)))
    }

    // ---- 截图（返回图像内容块） ----

    private fun screenshotTool(): McpTool = McpTool(
        name = "screenshot",
        description = "捕获当前屏幕，返回一张图像，并附带 image_size（图像像素）与 " +
            "screen_size（真实屏幕像素）。可用 `scale` 缩小、`region=[l,t,r,b]` 只截局部、`format` 选 " +
            "jpeg/webp。注意 click 使用 screen_size 坐标系，或直接用 normalized 坐标，避免按 " +
            "image_size 误算；带 region 时请改用屏幕像素坐标。",
        inputSchema = McpSchemas.objectSchema(
            mapOf(
                "scale" to McpSchemas.number("缩放比例 0.1~1，默认 1（原图）"),
                "quality" to McpSchemas.integer("压缩质量 1~100，默认 90"),
                "region" to McpSchemas.intArray("裁剪区域 [left,top,right,bottom]（屏幕像素），省略为整屏"),
                "format" to McpSchemas.enum("图像格式", listOf("jpeg", "webp")),
            ),
        ),
        readOnly = true,
    ) { args ->
        val source = bundle.screenshotProvider()
            ?: return@McpTool McpToolResult.error("截图失败：无可用截图后端（需屏幕捕获 / 无障碍 / Shizuku / Root）")
        val scale = args.optDouble("scale", 1.0).coerceIn(0.1, 1.0)
        val quality = args.optInt("quality", 90).coerceIn(1, 100)
        val region = args.optJSONArray("region")?.let { array ->
            if (array.length() < 4) null else Rect(
                array.optInt(0),
                array.optInt(1),
                array.optInt(2),
                array.optInt(3),
            )
        }
        val format = ScreenshotFormat.from(args.optString("format"))
        val encoded = try {
            ScreenshotEncoder.encode(source, (scale * 100).toInt(), quality, region, format)
        } finally {
            source.recycle()
        }
        val meta = JSONObject().apply {
            put("image_size", sizeOf(encoded.width, encoded.height))
            put("screen_size", sizeOf(encoded.screenWidth, encoded.screenHeight))
            encoded.region?.let { put("region", JSONArray(it)) }
        }
        McpToolResult(
            listOf(
                McpContent.image(encoded.base64, encoded.mimeType),
                McpContent.text(meta.toString()),
            ),
        )
    }

    private fun sizeOf(width: Int, height: Int): JSONObject =
        JSONObject().put("w", width).put("h", height)

    // ---- 异步结果查询（绕过设备队列，直接执行） ----

    private fun getResultTool(): McpTool = McpTool(
        name = "get_result",
        description = "查询一次异步操作的执行结果。当某次工具调用返回 {status:\"running\", request_id} " +
            "时使用——这表示动作已提交但结果未知，请勿盲目重试。",
        inputSchema = McpSchemas.objectSchema(
            mapOf("request_id" to McpSchemas.string("异步操作的 request_id")),
            required = listOf("request_id"),
        ),
        readOnly = true,
        synchronous = true,
    ) { args ->
        val id = args.optString("request_id")
        val op = operations.get(id)
            ?: return@McpTool McpToolResult.text(
                JSONObject().put("status", "not_found").put("request_id", id).toString(),
            )
        McpToolResult.text(operationJson(op))
    }

    private fun listOperationsTool(): McpTool = McpTool(
        name = "list_operations",
        description = "列出最近的异步操作及其状态（running / done / failed）。",
        inputSchema = McpSchemas.objectSchema(emptyMap()),
        readOnly = true,
        synchronous = true,
    ) {
        val array = JSONArray()
        operations.list().forEach { op ->
            array.put(
                JSONObject().apply {
                    put("request_id", op.id)
                    put("tool", op.tool)
                    put("status", op.status.name.lowercase())
                    put("elapsed_ms", op.elapsedMs)
                },
            )
        }
        McpToolResult.text(array.toString())
    }

    private fun operationJson(op: OperationState): String = JSONObject().apply {
        put("request_id", op.id)
        put("tool", op.tool)
        put("status", op.status.name.lowercase())
        put("elapsed_ms", op.elapsedMs)
        when (op.status) {
            OperationStatus.DONE -> op.result?.let { put("result", it.toJson()) }
            OperationStatus.FAILED -> put("error", op.error ?: "执行失败")
            OperationStatus.RUNNING -> put("message", "仍在执行中，请稍后再查询")
        }
    }.toString()

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

    private companion object {
        val READ_ONLY_SHARED = setOf(
            "ocr",
            "ui_dump",
            "find",
            "info",
            "notifications",
        )
    }
}
