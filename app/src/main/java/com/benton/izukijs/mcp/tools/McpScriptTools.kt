package com.benton.izukijs.mcp.tools

import com.benton.izukijs.core.log.RunLogRegistry
import com.benton.izukijs.data.ScriptRepository
import com.benton.izukijs.data.ScriptStaleException
import com.benton.izukijs.data.ScriptUpdateResult
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
        description = "读取指定脚本的源代码。传 offset / limit / meta 时返回带 sha256、总行数的 JSON，" +
            "支持按行分页；不传分页参数时保持原有纯文本返回。",
        inputSchema = McpSchemas.objectSchema(
            mapOf(
                "id" to McpSchemas.string("脚本 id"),
                "offset" to McpSchemas.integer("可选，0 基起始行；越界时返回空内容"),
                "limit" to McpSchemas.integer("可选，返回行数；0 或缺省表示取剩余全部"),
                "line_numbers" to McpSchemas.boolean(
                    "是否给每行加行号前缀，默认 false；加前缀后不要直接复制进 script_edit.oldText",
                ),
                "meta" to McpSchemas.boolean("是否返回 JSON（含 sha256 / total_lines），默认在传分页参数时自动开启"),
            ),
            required = listOf("id"),
        ),
        readOnly = true,
    ) { args ->
        val id = args.getString("id")
        val script = scriptRepository.find(id)
            ?: return@McpTool McpToolResult.error("找不到脚本：$id")
        val content = scriptRepository.read(script)
        val paged = args.has("offset") || args.has("limit") ||
            args.has("line_numbers") || args.optBoolean("meta", false)
        if (!paged) {
            return@McpTool McpToolResult.text(content.ifBlank { "（脚本内容为空）" })
        }
        val slice = ScriptText.slice(
            content,
            offset = args.optInt("offset", 0),
            limit = args.optInt("limit", 0),
        )
        val body = if (args.optBoolean("line_numbers", false)) {
            ScriptText.numberLines(slice.content, slice.offset + 1)
        } else {
            slice.content
        }
        McpToolResult.text(
            JSONObject().apply {
                put("id", script.id)
                put("name", script.name)
                put("content", body)
                put("offset", slice.offset)
                put("limit", slice.count)
                put("total_lines", slice.total)
                put("truncated", slice.truncated)
                put("next_offset", if (slice.truncated) slice.nextOffset else JSONObject.NULL)
                put("sha256", ScriptRepository.sha256(content))
            }.toString(),
        )
    },
    McpTool(
        name = "script_edit",
        description = "增量编辑脚本：按顺序用 newText 精确替换 oldText。oldText 必须与原文完全一致" +
            "（含缩进和换行，不要带行号前缀）；任一条失败则整批不落盘。建议先 read_script / " +
            "script_search 定位，必要时先 dry_run=true 预演。",
        inputSchema = McpSchemas.objectSchema(
            mapOf(
                "id" to McpSchemas.string("脚本 id"),
                "edits" to McpSchemas.objectArray(
                    "按顺序应用的编辑列表（最多 ${ScriptEditEngine.MAX_EDITS} 条）",
                    mapOf(
                        "oldText" to McpSchemas.string("要精确匹配的原始片段；不能为空"),
                        "newText" to McpSchemas.string("替换后的文本；空字符串表示删除"),
                        "replaceAll" to McpSchemas.boolean("oldText 命中多处时是否全部替换，默认 false"),
                    ),
                    required = listOf("oldText", "newText"),
                ),
                "dry_run" to McpSchemas.boolean("仅预演并返回 diff，不写盘，默认 false"),
                "expectedSha256" to McpSchemas.string("可选；与当前内容不一致时拒绝编辑，避免覆盖并发修改"),
            ),
            required = listOf("id", "edits"),
        ),
    ) { args ->
        val id = args.optString("id").trim()
        val script = scriptRepository.find(id)
            ?: return@McpTool McpToolResult.error("找不到脚本：$id")
        val rawEdits = args.optJSONArray("edits")
            ?: return@McpTool McpToolResult.error("缺少 edits")
        if (rawEdits.length() == 0) {
            return@McpTool scriptEditFailure("invalid", -1, "edits 不能为空")
        }
        if (rawEdits.length() > ScriptEditEngine.MAX_EDITS) {
            return@McpTool scriptEditFailure(
                "invalid",
                -1,
                "一次最多应用 ${ScriptEditEngine.MAX_EDITS} 条编辑",
            )
        }
        val edits = ArrayList<ScriptEdit>(rawEdits.length())
        for (index in 0 until rawEdits.length()) {
            val item = rawEdits.optJSONObject(index)
                ?: return@McpTool scriptEditFailure("invalid", index, "edits[$index] 必须是对象")
            if (!item.has("oldText") || !item.has("newText")) {
                return@McpTool scriptEditFailure(
                    "invalid",
                    index,
                    "edits[$index] 需要 oldText 与 newText",
                )
            }
            edits.add(
                ScriptEdit(
                    oldText = item.optString("oldText"),
                    newText = item.optString("newText"),
                    replaceAll = item.optBoolean("replaceAll", false),
                ),
            )
        }
        val expected = args.optString("expectedSha256").trim().ifEmpty { null }
        val dryRun = args.optBoolean("dry_run", false)

        if (dryRun) {
            val previous = scriptRepository.read(script)
            if (expected != null) {
                val actual = ScriptRepository.sha256(previous)
                if (expected != actual) {
                    return@McpTool scriptEditStale("脚本内容已在别处修改，请重新读取后再编辑", actual)
                }
            }
            val result = runCatching { ScriptEditEngine.apply(previous, edits) }
                .getOrElse { error ->
                    if (error is ScriptEditException) return@McpTool scriptEditFailure(error)
                    throw error
                }
            return@McpTool McpToolResult.text(
                scriptEditPayload(script, previous, result, dryRun = true, update = null),
            )
        }

        var previousText: String? = null
        var edited: ScriptEditResult? = null
        val update: ScriptUpdateResult = try {
            scriptRepository.update(script, expected) { current ->
                previousText = current
                val applied = ScriptEditEngine.apply(current, edits)
                edited = applied
                applied.content
            }
        } catch (error: ScriptEditException) {
            return@McpTool scriptEditFailure(error)
        } catch (error: ScriptStaleException) {
            return@McpTool scriptEditStale(
                error.message ?: "脚本内容已在别处修改，请重新读取后再编辑",
                error.actualSha256,
            )
        }
        McpToolResult.text(
            scriptEditPayload(
                script = script,
                previous = previousText ?: "",
                result = edited ?: return@McpTool McpToolResult.error("编辑结果为空"),
                dryRun = false,
                update = update,
            ),
        )
    },
    McpTool(
        name = "script_search",
        description = "在脚本中搜索文本并返回行号、列号与上下文。省略 id 时搜索全部脚本；" +
            "适合先定位再调用 script_edit，避免整文件读取。",
        inputSchema = McpSchemas.objectSchema(
            mapOf(
                "id" to McpSchemas.string("可选；脚本 id，省略则搜索全部脚本"),
                "pattern" to McpSchemas.string("搜索内容"),
                "mode" to McpSchemas.enum("匹配方式，默认 literal", listOf("literal", "regex")),
                "context_lines" to McpSchemas.integer("上下文行数，默认 1，范围 0~5"),
                "max_results" to McpSchemas.integer("最多返回条数，默认 50，上限 200"),
            ),
            required = listOf("pattern"),
        ),
        readOnly = true,
    ) { args ->
        val pattern = args.optString("pattern")
        if (pattern.isEmpty()) {
            return@McpTool McpToolResult.error("pattern 不能为空")
        }
        if (pattern.length > MAX_SEARCH_PATTERN) {
            return@McpTool McpToolResult.error("pattern 过长，上限 $MAX_SEARCH_PATTERN 字符")
        }
        val regex = if (args.optString("mode", "literal").equals("regex", ignoreCase = true)) {
            runCatching { Regex(pattern) }.getOrElse {
                return@McpTool McpToolResult.error("正则无效：${it.message ?: "解析失败"}")
            }
        } else {
            null
        }
        val contextLines = args.optInt("context_lines", 1).coerceIn(0, 5)
        val maxResults = args.optInt("max_results", 50).coerceIn(1, 200)
        val id = args.optString("id").trim()
        val scripts = if (id.isEmpty()) {
            scriptRepository.list()
        } else {
            listOfNotNull(scriptRepository.find(id))
        }
        if (scripts.isEmpty()) {
            return@McpTool McpToolResult.error("找不到脚本：$id")
        }
        val matches = JSONArray()
        var truncated = false
        outer@ for (script in scripts) {
            val content = scriptRepository.read(script)
            val starts = ScriptText.lineStarts(content)
            for (hit in searchHits(content, pattern, regex)) {
                if (matches.length() >= maxResults) {
                    truncated = true
                    break@outer
                }
                val lineIndex = lineIndexAt(starts, hit)
                val line = lineIndex + 1
                matches.put(
                    JSONObject().apply {
                        put("id", script.id)
                        put("name", script.name)
                        put("line", line)
                        put("column", hit - starts[lineIndex] + 1)
                        put("text", clipLine(ScriptText.lineAt(content, line)))
                        if (contextLines > 0) {
                            put(
                                "before",
                                JSONArray().apply {
                                    for (offset in contextLines downTo 1) {
                                        if (line - offset >= 1) {
                                            put(clipLine(ScriptText.lineAt(content, line - offset)))
                                        }
                                    }
                                },
                            )
                            put(
                                "after",
                                JSONArray().apply {
                                    for (offset in 1..contextLines) {
                                        if (line + offset <= ScriptText.lineCount(content)) {
                                            put(clipLine(ScriptText.lineAt(content, line + offset)))
                                        }
                                    }
                                },
                            )
                        }
                    },
                )
            }
        }
        McpToolResult.text(
            JSONObject().apply {
                put("ok", true)
                put("match_count", matches.length())
                put("truncated", truncated)
                put("matches", matches)
            }.toString(),
        )
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
        val started = executionManager.run(
            script.id,
            script.name,
            scriptRepository.read(script),
            values,
            trigger = com.benton.izukijs.runtime.RunTrigger.MCP,
        )
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
        if (!scriptRepository.delete(script)) {
            return@McpTool McpToolResult.error("删除脚本失败：${script.name}")
        }
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

private fun scriptEditPayload(
    script: ScriptInfo,
    previous: String,
    result: ScriptEditResult,
    dryRun: Boolean,
    update: ScriptUpdateResult?,
): String = JSONObject().apply {
    put("ok", true)
    put("id", script.id)
    put("name", script.name)
    put("dry_run", dryRun)
    put("changed", result.changed)
    put("bytes", result.content.toByteArray(Charsets.UTF_8).size)
    put("sha256", update?.sha256 ?: ScriptRepository.sha256(result.content))
    put("old_lines", ScriptRepository.lineCount(previous))
    put("new_lines", ScriptRepository.lineCount(result.content))
    put("noop", result.noopCount)
    put(
        "edits",
        JSONArray().apply {
            result.outcomes.forEach { outcome ->
                put(
                    JSONObject().apply {
                        put("index", outcome.index)
                        put("matches", outcome.matches)
                        put("changed", outcome.changed)
                        put("line", outcome.line)
                    },
                )
            }
        },
    )
    put("diff", ScriptTextDiff.unified(previous, result.content))
}.toString()

private fun scriptEditFailure(error: ScriptEditException): McpToolResult =
    scriptEditFailure(error.reason, error.editIndex, error.message ?: "编辑失败", error.occurrences)

private fun scriptEditFailure(
    reason: String,
    editIndex: Int,
    message: String,
    occurrences: List<ScriptOccurrence> = emptyList(),
): McpToolResult = McpToolResult(
    listOf(
        McpContent.text(
            JSONObject().apply {
                put("ok", false)
                put("stage", "script_edit")
                put("reason", reason)
                put("edit_index", editIndex)
                put("message", message)
                put(
                    "occurrences",
                    JSONArray().apply {
                        occurrences.forEach { occurrence ->
                            put(
                                JSONObject().apply {
                                    put("line", occurrence.line)
                                    put("preview", occurrence.preview)
                                },
                            )
                        }
                    },
                )
                put("retryable", true)
            }.toString(),
        ),
    ),
    isError = true,
)

private fun scriptEditStale(message: String, actualSha256: String): McpToolResult = McpToolResult(
    listOf(
        McpContent.text(
            JSONObject().apply {
                put("ok", false)
                put("stage", "script_edit")
                put("reason", "stale")
                put("message", message)
                put("actual_sha256", actualSha256)
                put("retryable", true)
            }.toString(),
        ),
    ),
    isError = true,
)

private fun searchHits(content: String, pattern: String, regex: Regex?): Sequence<Int> = sequence {
    if (regex != null) {
        for (match in regex.findAll(content)) {
            if (match.range.isEmpty()) continue
            yield(match.range.first)
        }
    } else {
        var start = 0
        while (true) {
            val at = content.indexOf(pattern, start)
            if (at < 0) break
            yield(at)
            start = at + pattern.length.coerceAtLeast(1)
        }
    }
}

private fun lineIndexAt(starts: IntArray, index: Int): Int {
    val found = starts.binarySearch(index)
    return if (found >= 0) found else -found - 2
}

/** 搜索结果里的行文本上限，避免压缩后的超长单行撑爆上下文。 */
private fun clipLine(value: String, maxChars: Int = 300): String =
    if (value.length <= maxChars) value else value.take(maxChars) + "…"

private fun McpToolRegistry.scriptCreatedAt(script: ScriptInfo): Long {
    val createdAt = runCatching {
        Files.readAttributes(script.file.toPath(), BasicFileAttributes::class.java)
            .creationTime()
            .toMillis()
    }.getOrDefault(0L)
    return if (createdAt > 0L) createdAt else script.updatedAt
}
