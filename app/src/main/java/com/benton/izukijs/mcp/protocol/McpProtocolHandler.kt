package com.benton.izukijs.mcp.protocol

import com.benton.izukijs.mcp.resources.McpResources
import com.benton.izukijs.mcp.tools.McpToolResult
import com.benton.izukijs.mcp.tools.McpToolRegistry
import com.benton.izukijs.mcp.tools.OperationRegistry
import com.benton.izukijs.runtime.LogBus
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
    private val operations: OperationRegistry,
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

        // 结果查询类工具在请求线程直接执行：它们只读登记表，若排到设备队列末尾，
        // 一旦前面的操作卡住就永远查不到结果。
        if (tool.synchronous) {
            val result = runCatching { tool.call(arguments) }
                .getOrElse { McpToolResult.error("工具执行失败：${it.message}") }
            return success(id, result.toJson())
        }

        logBus.info("🔌 MCP 调用工具 $name")
        val requestId = operations.create(name)
        val future = try {
            toolDispatcher.submit<McpToolResult> {
                val result = try {
                    tool.call(arguments)
                } catch (t: Throwable) {
                    operations.fail(requestId, t.message ?: t.javaClass.simpleName)
                    throw t
                }
                if (result.isError) {
                    operations.fail(requestId, result.content.firstOrNull()?.text ?: "执行失败")
                } else {
                    operations.succeed(requestId, result)
                }
                result
            }
        } catch (t: Throwable) {
            operations.fail(requestId, t.message ?: t.javaClass.simpleName)
            logBus.warn("MCP 工具 $name 无法调度：${t.message}")
            return failure(id, INTERNAL_ERROR, "工具执行异常：${t.message}")
        }

        return try {
            val result = future.get(SYNC_WAIT_SEC, TimeUnit.SECONDS)
            success(id, result.toJson())
        } catch (t: TimeoutException) {
            // 操作已提交到设备队列，是否已真正执行未知——返回句柄而非报错。
            logBus.warn("MCP 工具 $name 超过 ${SYNC_WAIT_SEC}s 仍未返回，转异步（request_id=$requestId）")
            success(id, runningResult(name, requestId))
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            logBus.warn("MCP 工具 $name 执行异常：${cause.message}")
            failure(id, INTERNAL_ERROR, "工具执行异常：${cause.message}")
        }
    }

    /** 异步句柄：说明操作已提交、结果未知，并给出回查方式。 */
    private fun runningResult(toolName: String, requestId: String): JSONObject {
        val payload = JSONObject().apply {
            put("status", "running")
            put("request_id", requestId)
            put("tool", toolName)
            put("message", "操作已提交，结果未知。稍后调用 get_result(request_id=\"$requestId\") 查询。")
        }
        return McpToolResult.text(payload.toString()).toJson()
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

        /** 同步等待窗口：超过则返回 request_id 转异步，避免客户端读超时。 */
        private const val SYNC_WAIT_SEC = 20L

        private val SUPPORTED_VERSIONS = setOf("2025-06-18", "2025-03-26", "2024-11-05")

        private val INSTRUCTIONS =
            "Izuki JS 运行于 Android，可读取控件树 / OCR / 截图，并通过无障碍 / Shizuku / Root / " +
                "蓝牙 HID 注入点击、滑动、文本与按键。坐标默认是全分辨率屏幕像素；click 支持 " +
                "normalized=true 的 0~1 归一化坐标（推荐，免疫截图缩放）。操作前建议先用 ocr / " +
                "ui_dump / screenshot 观察。耗时操作超过同步窗口会返回 {status:\"running\", request_id}，" +
                "此时动作可能已执行，请用 get_result 查询，不要直接重试。shell 与脚本执行默认关闭。"
    }
}
