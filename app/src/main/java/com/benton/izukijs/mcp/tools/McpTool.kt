package com.benton.izukijs.mcp.tools

import org.json.JSONArray
import org.json.JSONObject

/** MCP `tools/call` 返回内容块：文本或图像。 */
data class McpContent(
    val type: String,
    val text: String? = null,
    val data: String? = null,
    val mimeType: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        text?.let { put("text", it) }
        data?.let { put("data", it) }
        mimeType?.let { put("mimeType", it) }
    }

    companion object {
        fun text(value: String): McpContent = McpContent(type = "text", text = value)

        fun image(base64: String, mimeType: String): McpContent =
            McpContent(type = "image", data = base64, mimeType = mimeType)
    }
}

/** MCP `tools/call` 结果。 */
data class McpToolResult(
    val content: List<McpContent>,
    val isError: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("content", JSONArray().apply { content.forEach { put(it.toJson()) } })
        if (isError) put("isError", true)
    }

    companion object {
        fun text(value: String): McpToolResult = McpToolResult(listOf(McpContent.text(value)))

        fun error(message: String): McpToolResult =
            McpToolResult(listOf(McpContent.text(message)), isError = true)
    }
}

/**
 * 一个 MCP 工具：名称、描述、JSON Schema 与执行体。
 *
 * [handler] 内抛出的任何异常都会被 [call] 捕获并转换为 `isError` 结果，
 * 避免单个工具异常打断整个 JSON-RPC 会话。
 */
class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val readOnly: Boolean = false,
    private val handler: (JSONObject) -> McpToolResult,
) {
    fun call(arguments: JSONObject): McpToolResult =
        runCatching { handler(arguments) }
            .getOrElse { McpToolResult.error("工具执行失败：${it.message ?: it.javaClass.simpleName}") }

    /** 序列化为 MCP `tools/list` 元素。 */
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("inputSchema", inputSchema)
        if (readOnly) {
            put(
                "annotations",
                JSONObject().apply {
                    put("title", description)
                    put("readOnlyHint", true)
                },
            )
        }
    }
}

/** MCP 工具 JSON Schema 构造辅助。 */
object McpSchemas {

    fun objectSchema(
        properties: Map<String, JSONObject>,
        required: List<String> = emptyList(),
    ): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply { properties.forEach { (key, value) -> put(key, value) } })
        put("additionalProperties", false)
        if (required.isNotEmpty()) put("required", JSONArray(required))
    }

    fun string(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    fun number(description: String): JSONObject =
        JSONObject().put("type", "number").put("description", description)

    fun integer(description: String): JSONObject =
        JSONObject().put("type", "integer").put("description", description)

    fun boolean(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)

    fun stringArray(description: String): JSONObject = JSONObject()
        .put("type", "array")
        .put("description", description)
        .put("items", JSONObject().put("type", "string"))

    /** 手势点数组：`[{x,y,t?}, ...]` 或简写 `[[x,y,t?], ...]`。 */
    fun gestureStrokes(description: String): JSONObject = JSONObject()
        .put("type", "array")
        .put("description", description)
        .put("items", JSONObject().put("type", "array").put("items", JSONObject().put("type", "number")))
}
