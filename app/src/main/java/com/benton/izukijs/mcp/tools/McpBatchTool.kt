package com.benton.izukijs.mcp.tools

import com.benton.izukijs.ai.BatchRunner
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 级 batch：按工具注册表派发，因此可以调用 virtual_screen_input、脚本工具等
 * 只在 MCP 暴露的工具，而不是像共享 AgentTools 那样只认识阅读/控制基础工具。
 */
internal fun McpToolRegistry.batchTool(): McpTool = McpTool(
    name = "batch",
    description = "一次调用顺序执行多个动作：actions=[{tool,arguments}]；默认失败即停并回传每步结果" +
        "（含 elapsed_ms）。可调用任意已注册 MCP 工具（含 virtual_screen_input / virtual_screen）。" +
        "不支持嵌套 batch。",
    inputSchema = McpSchemas.objectSchema(
        mapOf(
            "actions" to McpSchemas.objectArray(
                "要顺序执行的动作列表",
                mapOf(
                    "tool" to McpSchemas.string("工具名"),
                    "arguments" to JSONObject()
                        .put("type", "object")
                        .put("description", "该工具的参数"),
                ),
                required = listOf("tool"),
            ),
            "continue_on_error" to McpSchemas.boolean("遇错是否继续执行后续动作，默认 false（即失败即停）"),
        ),
        required = listOf("actions"),
    ),
) { args ->
    val raw = args.optJSONArray("actions")
        ?: return@McpTool McpToolResult.error("batch: 需要 actions 数组")
    val actions = ArrayList<BatchRunner.Action<JSONObject>>(raw.length())
    for (i in 0 until raw.length()) {
        val item = raw.optJSONObject(i)
            ?: return@McpTool McpToolResult.error("batch: 第 ${i + 1} 个动作不是对象")
        val tool = item.optString("tool")
        if (tool.isBlank()) {
            return@McpTool McpToolResult.error("batch: 第 ${i + 1} 个动作缺少 tool")
        }
        if (tool == "batch") {
            return@McpTool McpToolResult.error("batch: 不支持嵌套 batch")
        }
        actions.add(BatchRunner.Action(tool, item.optJSONObject("arguments") ?: JSONObject()))
    }

    val outcome = BatchRunner.run(
        actions = actions,
        continueOnError = args.optBoolean("continue_on_error", false),
        execute = { tool, actionArgs -> executeTool(tool, actionArgs) },
        isFailure = ::isFailureResult,
    )
    McpToolResult.text(
        JSONObject().apply {
            put("ok", outcome.ok)
            put("executed", outcome.steps.size)
            put("stopped_early", outcome.stoppedEarly)
            put("truncated", outcome.truncated)
            put(
                "steps",
                JSONArray().apply {
                    outcome.steps.forEach { step ->
                        put(
                            JSONObject().apply {
                                put("index", step.index)
                                put("tool", step.tool)
                                put("ok", step.ok)
                                put("elapsed_ms", step.elapsedMs)
                                put("result", step.result)
                            },
                        )
                    }
                },
            )
        }.toString(),
    )
}

private fun McpToolRegistry.executeTool(tool: String, args: JSONObject): String {
    val target = find(tool)
        ?: return "$ERROR_PREFIX 未知工具：$tool"
    val result = target.call(args)
    val text = result.content
        .filter { it.type == "text" }
        .mapNotNull { it.text }
        .joinToString("\n")
    val hasImage = result.content.any { it.type == "image" }
    val body = buildString {
        if (result.isError && !text.startsWith(ERROR_PREFIX)) append(ERROR_PREFIX).append(' ')
        append(text)
        if (hasImage) {
            if (isNotEmpty()) append('\n')
            append("[image]")
        }
    }
    return body.ifBlank { "$ERROR_PREFIX 工具 $tool 无文本结果" }
}

private fun isFailureResult(result: String): Boolean {
    val trimmed = result.trim()
    if (trimmed.isEmpty()) return true
    if (trimmed.startsWith(ERROR_PREFIX)) return true
    if (!trimmed.startsWith("{")) return false
    return runCatching { !JSONObject(trimmed).optBoolean("ok", true) }.getOrDefault(false)
}

private const val ERROR_PREFIX = "[错误]"
