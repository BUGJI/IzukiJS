package com.benton.izukijs.mcp.protocol

import com.benton.izukijs.mcp.resources.McpResources
import com.benton.izukijs.mcp.tools.McpToolResult
import com.benton.izukijs.mcp.tools.McpToolRegistry
import com.benton.izukijs.runtime.LogBus
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * MCP（Model Context Protocol）JSON-RPC 2.0 处理器（Streamable HTTP 的 JSON 响应模式）。
 *
 * 支持 `initialize` / `ping` / tools / resources / prompts / `logging/setLevel`，
 * 以及 JSON-RPC 批量请求。工具执行被派发到单线程 [toolDispatcher]，保证手势、OCR 等
 * 设备操作串行，不会互相打断。
 */
class McpProtocolHandler(
    private val registry: McpToolRegistry,
    private val resources: McpResources,
    private val logBus: LogBus,
    private val toolDispatcher: ExecutorService,
    private val serverVersion: String,
) {

    /**
     * 处理一段 JSON 文本，返回要回写的 JSON 文本；
     * 若整段都是通知（无需响应）则返回 null。
     */
    fun handle(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return failure(null, PARSE_ERROR, "空请求体").toString()
        val root = try {
            JSONTokener(trimmed).nextValue()
        } catch (t: Throwable) {
            return failure(null, PARSE_ERROR, "JSON 解析失败：${t.message}").toString()
        }
        return when (root) {
            is JSONArray -> handleBatch(root)?.toString()
            is JSONObject -> handleSingle(root)?.toString()
            else -> failure(null, INVALID_REQUEST, "请求必须是 JSON 对象或数组").toString()
        }
    }

    private fun handleBatch(batch: JSONArray): JSONArray? {
        if (batch.length() == 0) {
            return JSONArray().put(failure(null, INVALID_REQUEST, "批量请求不能为空"))
        }
        val responses = JSONArray()
        for (i in 0 until batch.length()) {
            val item = batch.optJSONObject(i)
            if (item == null) {
                responses.put(failure(null, INVALID_REQUEST, "批量元素必须是 JSON 对象"))
                continue
            }
            handleSingle(item)?.let { responses.put(it) }
        }
        return if (responses.length() == 0) null else responses
    }

    private fun handleSingle(request: JSONObject): JSONObject? {
        val hasId = request.has("id")
        val id = if (hasId) request.opt("id") else null
        val method = request.optString("method", "")
        if (method.isEmpty()) {
            return if (hasId) failure(id, INVALID_REQUEST, "缺少 method") else null
        }
        val params = request.optJSONObject("params") ?: JSONObject()

        val response = when (method) {
            "notifications/initialized", "notifications/cancelled" -> null
            "initialize" -> success(id, initialize(params))
            "ping" -> success(id, JSONObject())
            "tools/list" -> success(id, toolsList())
            "tools/call" -> toolsCall(id, params)
            "resources/list" -> success(id, JSONObject().put("resources", resources.listJson()))
            "resources/templates/list" ->
                success(id, JSONObject().put("resourceTemplates", resources.templatesJson()))
            "resources/read" -> resourcesRead(id, params)
            "prompts/list" -> success(id, JSONObject().put("prompts", JSONArray()))
            "logging/setLevel" -> success(id, JSONObject())
            else -> failure(id, METHOD_NOT_FOUND, "未知方法：$method")
        }
        // 通知（无 id）不产生响应。
        return if (hasId) response else null
    }

    // ---- 方法实现 ----

    private fun initialize(params: JSONObject): JSONObject {
        val requested = params.optString("protocolVersion")
        val negotiated = if (requested in SUPPORTED_VERSIONS) requested else LATEST_VERSION
        return JSONObject().apply {
            put("protocolVersion", negotiated)
            put(
                "capabilities",
                JSONObject().apply {
                    put("tools", JSONObject().put("listChanged", false))
                    put(
                        "resources",
                        JSONObject().put("subscribe", false).put("listChanged", false),
                    )
                    put("logging", JSONObject())
                },
            )
            put(
                "serverInfo",
                JSONObject()
                    .put("name", SERVER_NAME)
                    .put("title", "Izuki JS")
                    .put("version", serverVersion),
            )
            put("instructions", INSTRUCTIONS)
        }
    }

    private fun toolsList(): JSONObject {
        val array = JSONArray()
        registry.tools().forEach { array.put(it.toJson()) }
        return JSONObject().put("tools", array)
    }

    private fun toolsCall(id: Any?, params: JSONObject): JSONObject {
        val name = params.optString("name")
        if (name.isBlank()) return failure(id, INVALID_PARAMS, "缺少工具名 name")
        val tool = registry.find(name)
            ?: return failure(id, INVALID_PARAMS, "未知工具：$name")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        logBus.info("🔌 MCP 调用工具 $name")
        val result: McpToolResult = try {
            toolDispatcher.submit<McpToolResult> { tool.call(arguments) }
                .get(TOOL_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            logBus.warn("MCP 工具 $name 执行异常：${t.message}")
            return failure(id, INTERNAL_ERROR, "工具执行异常：${t.message}")
        }
        return success(id, result.toJson())
    }

    private fun resourcesRead(id: Any?, params: JSONObject): JSONObject {
        val uri = params.optString("uri")
        if (uri.isBlank()) return failure(id, INVALID_PARAMS, "缺少资源 uri")
        val content = resources.read(uri)
            ?: return failure(id, INVALID_PARAMS, "找不到资源：$uri")
        val contents = JSONArray().put(
            JSONObject()
                .put("uri", content.uri)
                .put("mimeType", content.mimeType)
                .put("text", content.text),
        )
        return success(id, JSONObject().put("contents", contents))
    }

    // ---- 响应构造 ----

    private fun success(id: Any?, result: JSONObject): JSONObject = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", id ?: JSONObject.NULL)
        put("result", result)
    }

    private fun failure(id: Any?, code: Int, message: String): JSONObject = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", id ?: JSONObject.NULL)
        put(
            "error",
            JSONObject().apply {
                put("code", code)
                put("message", message)
            },
        )
    }

    companion object {
        const val LATEST_VERSION = "2025-06-18"
        const val SERVER_NAME = "izuki-mobile"

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603

        private const val TOOL_TIMEOUT_SEC = 60L

        private val SUPPORTED_VERSIONS = setOf("2025-06-18", "2025-03-26", "2024-11-05")

        private val INSTRUCTIONS =
            "Izuki JS 运行于 Android，可读取无障碍控件树、执行 OCR 与截图，并通过无障碍 / " +
                "Shizuku / Root / 蓝牙 HID 注入点击、滑动、文本与按键。shell 与脚本执行默认关闭，" +
                "需在 App 内显式开启。操作前建议先用 ui_dump / ocr_screen / screenshot 观察界面。"
    }
}
