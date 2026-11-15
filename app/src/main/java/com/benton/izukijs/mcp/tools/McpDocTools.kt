package com.benton.izukijs.mcp.tools

import com.benton.izukijs.mcp.resources.McpDocCatalog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 开发文档工具：`list` 返回索引，`read` 按 name 分页读取正文。
 *
 * 不访问设备，因此与 `read_logs` 一样标 `synchronous = true`，避免排在单线程设备队列
 * 后面被长任务堵住。文档来自 APK 内 assets/docs（仓库 docs 目录下 Markdown 的镜像）。
 */
internal fun docsTool(docs: McpDocCatalog): McpTool = McpTool(
    name = "docs",
    description = "读取打包在应用内的开发文档（仓库 docs/ 镜像）：action=list 列出文档，" +
        "action=read 按 name 读取正文。文档可能滞后于当前版本；大文档用 offset / max_chars 续读。",
    inputSchema = McpSchemas.objectSchema(
        mapOf(
            "action" to McpSchemas.enum("list 列出全部文档；read 读取正文", listOf("list", "read")),
            "name" to McpSchemas.string("文档 id（list 返回的 id，或文件名，可省略 .md），如 SCRIPT_API"),
            "offset" to McpSchemas.integer("read 的字符偏移，默认 0；用上次返回的 next_offset 续读"),
            "max_chars" to McpSchemas.integer(
                "read 单次返回的字符数，默认 ${McpDocCatalog.DEFAULT_MAX_CHARS}，" +
                    "范围 ${McpDocCatalog.MIN_MAX_CHARS}~${McpDocCatalog.MAX_MAX_CHARS}",
            ),
        ),
    ),
    readOnly = true,
    synchronous = true,
) { args ->
    when (args.optString("action").trim().lowercase()) {
        "", "list" -> McpToolResult.text(docs.indexJson().toString())
        "read" -> readDoc(docs, args)
        else -> McpToolResult.text(
            JSONObject()
                .put("ok", false)
                .put("message", "未知 action，可用 list / read")
                .toString(),
        )
    }
}

private fun readDoc(docs: McpDocCatalog, args: JSONObject): McpToolResult {
    val name = args.optString("name").trim()
    val result = docs.read(
        name = name,
        offset = args.optInt("offset", 0),
        maxChars = args.optInt("max_chars", McpDocCatalog.DEFAULT_MAX_CHARS),
    )
    if (result != null) return McpToolResult.text(result.toString())

    val available = JSONArray()
    docs.documents().forEach { available.put(it.id) }
    return McpToolResult.text(
        JSONObject()
            .put("ok", false)
            .put(
                "message",
                if (name.isEmpty()) "缺少 name；先用 action=list 查看可用文档"
                else "找不到文档：$name",
            )
            .put("available", available)
            .toString(),
    )
}
