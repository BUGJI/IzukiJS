package com.benton.izukijs.mcp.tools

import com.benton.izukijs.core.log.RunLogRegistry
import com.benton.izukijs.model.ScriptInfo
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import org.json.JSONArray
import org.json.JSONObject

// ---- 脚本（受 allowScripts 控制） ----

internal fun McpToolRegistry.scriptTools(): List<McpTool> = listOf(
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
        name = "script_write",
        description = "新建或更新脚本源码。id 命中已有脚本则覆盖并保留历史版本，否则新建；" +
            "返回真实 id（可能因重名加后缀而与传入 id 不同），后续更新请使用该 id。",
        inputSchema = McpSchemas.objectSchema(
            mapOf(
                "id" to McpSchemas.string("目标脚本 id；已存在则更新"),
                "name" to McpSchemas.string("新建时的名称，缺省用 id"),
                "content" to McpSchemas.string("完整脚本源码（UTF-8，最大 1 MiB）"),
                "createIfMissing" to McpSchemas.boolean("id 不存在时是否新建，默认 true"),
            ),
            required = listOf("content"),
        ),
    ) { args ->
        if (!args.has("content")) {
            return@McpTool McpToolResult.error("缺少脚本源码 content")
        }
        val content = args.optString("content")
        val bytes = content.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_SCRIPT_BYTES) {
            return@McpTool McpToolResult.error(
                "脚本源码过大：$bytes 字节，上限 $MAX_SCRIPT_BYTES 字节",
            )
        }
        val id = args.optString("id").trim()
        val name = args.optString("name").trim()
        val targetId = id.ifEmpty { name }
        if (targetId.isEmpty()) {
            return@McpTool McpToolResult.error("需要提供 id 或 name")
        }
        val existing = scriptRepository.find(targetId)
        if (existing == null && !args.optBoolean("createIfMissing", true)) {
            return@McpTool McpToolResult.error("找不到脚本：$targetId")
        }
        val created = existing == null
        val script = if (existing != null) {
            scriptRepository.write(existing, content, forceHistory = true)
            scriptRepository.find(existing.id) ?: existing
        } else {
            scriptRepository.create(name.ifEmpty { id }, content)
        }
        McpToolResult.text(
            JSONObject().apply {
                put("id", script.id)
                put("name", script.name)
                put("createdAt", scriptCreatedAt(script))
                put("updatedAt", script.updatedAt)
                put("bytes", script.file.length())
                put("created", created)
            }.toString(),
        )
    },
    McpTool(
        name = "run_script",
        description = "运行指定脚本。立即返回 run_id；用 get_run_log / read_logs(run_id=...) 取回配对的输出与退出状态。",
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
        val started = executionManager.run(script.id, script.name, scriptRepository.read(script), values)
        if (!started) {
            return@McpTool McpToolResult.text(
                JSONObject().put("ok", false).put("message", "脚本启动失败").toString(),
            )
        }
        val runId = executionManager.lastRunId()
        val record = runId?.let { RunLogRegistry.find(it) }
        McpToolResult.text(
            JSONObject().apply {
                put("ok", true)
                put("run_id", runId)
                put("script_id", script.id)
                put("name", script.name)
                put("startedAt", record?.startedAt ?: System.currentTimeMillis())
            }.toString(),
        )
    },
    McpTool(
        name = "delete_script",
        description = "删除指定脚本（同时清理历史版本）。运行中的脚本不可删除。",
        inputSchema = McpSchemas.objectSchema(
            mapOf("id" to McpSchemas.string("脚本 id")),
            required = listOf("id"),
        ),
    ) { args ->
        val id = args.optString("id").trim()
        val script = scriptRepository.find(id)
            ?: return@McpTool McpToolResult.error("找不到脚本：$id")
        if (executionManager.runningScriptId.value == script.id) {
            return@McpTool McpToolResult.error("脚本正在运行，无法删除：${script.name}")
        }
        scriptRepository.delete(script)
        McpToolResult.text(
            JSONObject().put("ok", true).put("id", script.id).put("deleted", true).toString(),
        )
    },
    McpTool(
        name = "rename_script",
        description = "重命名脚本。返回真实新 id（重名时可能加后缀）；运行中的脚本不可改名。",
        inputSchema = McpSchemas.objectSchema(
            mapOf(
                "id" to McpSchemas.string("当前脚本 id"),
                "name" to McpSchemas.string("新名称"),
            ),
            required = listOf("id", "name"),
        ),
    ) { args ->
        val id = args.optString("id").trim()
        val name = args.optString("name").trim()
        if (name.isEmpty()) return@McpTool McpToolResult.error("rename_script 需要 name")
        val script = scriptRepository.find(id)
            ?: return@McpTool McpToolResult.error("找不到脚本：$id")
        if (executionManager.runningScriptId.value == script.id) {
            return@McpTool McpToolResult.error("脚本正在运行，无法改名：${script.name}")
        }
        if (name == script.id) {
            return@McpTool McpToolResult.text(
                JSONObject().put("ok", true).put("id", script.id).put("name", script.name).toString(),
            )
        }
        val renamed = scriptRepository.rename(script, name)
        McpToolResult.text(
            JSONObject().apply {
                put("ok", true)
                put("id", renamed.id)
                put("name", renamed.name)
            }.toString(),
        )
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

private fun McpToolRegistry.scriptCreatedAt(script: ScriptInfo): Long {
    val createdAt = runCatching {
        Files.readAttributes(script.file.toPath(), BasicFileAttributes::class.java)
            .creationTime()
            .toMillis()
    }.getOrDefault(0L)
    return if (createdAt > 0L) createdAt else script.updatedAt
}
