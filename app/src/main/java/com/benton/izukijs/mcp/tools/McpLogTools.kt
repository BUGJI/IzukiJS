package com.benton.izukijs.mcp.tools

import com.benton.izukijs.core.log.LogLevel
import com.benton.izukijs.core.log.RunLogRecord
import com.benton.izukijs.core.log.RunLogRegistry
import com.benton.izukijs.core.log.RunStatus
import org.json.JSONArray
import org.json.JSONObject

// ---- 日志 ----

internal fun McpToolRegistry.readLogsTool(): McpTool = McpTool(
    name = "read_logs",
    description = "读取应用最近日志的尾部，可按级别 / 时间 / 关键字过滤；传 run_id 可读取某次脚本运行的配对日志。",
    inputSchema = McpSchemas.objectSchema(
        mapOf(
            "lines" to McpSchemas.integer("返回行数 1~2000，默认 200"),
            "level" to McpSchemas.enum(
                "最低日志级别（含）",
                listOf("debug", "info", "success", "warn", "error"),
            ),
            "since_ms" to McpSchemas.integer("只返回不早于该时间戳的日志"),
            "grep" to McpSchemas.string("按关键字过滤（不区分大小写）"),
            "run_id" to McpSchemas.string("只读取该次脚本运行的日志，通常来自 run_script 的返回"),
        ),
    ),
    readOnly = true,
    synchronous = true,
) { args ->
    val lines = args.optInt("lines", 200).coerceIn(1, 2000)
    val level = parseLogLevel(args.optString("level"))
    val grep = args.optString("grep").takeIf { it.isNotBlank() }
    val runId = args.optString("run_id").trim()
    if (runId.isNotEmpty()) {
        val record = RunLogRegistry.find(runId)
            ?: return@McpTool McpToolResult.text(
                JSONObject().put("ok", false).put("message", "找不到运行记录：$runId").toString(),
            )
        return@McpTool McpToolResult.text(runLogJson(record, lines, level, grep).toString())
    }
    McpToolResult.text(
        logFileStore.query(
            maxLines = lines,
            level = level?.name,
            since = args.optLong("since_ms", 0L),
            grep = grep,
        ),
    )
}

internal fun McpToolRegistry.getRunLogTool(): McpTool = McpTool(
    name = "get_run_log",
    description = "读取一次脚本运行的状态与配对输出（run_id 由 run_script 返回）。",
    inputSchema = McpSchemas.objectSchema(
        mapOf(
            "run_id" to McpSchemas.string("run_script 返回的 run_id"),
            "tail" to McpSchemas.integer("返回最后 N 条日志，默认 200"),
            "level" to McpSchemas.enum(
                "最低日志级别（含）",
                listOf("debug", "info", "success", "warn", "error"),
            ),
            "grep" to McpSchemas.string("按关键字过滤（不区分大小写）"),
        ),
        required = listOf("run_id"),
    ),
    readOnly = true,
    synchronous = true,
) { args ->
    val runId = args.optString("run_id").trim()
    val record = RunLogRegistry.find(runId)
        ?: return@McpTool McpToolResult.text(
            JSONObject().put("ok", false).put("message", "找不到运行记录：$runId").toString(),
        )
    McpToolResult.text(
        runLogJson(
            record,
            args.optInt("tail", 200).coerceIn(1, 2000),
            parseLogLevel(args.optString("level")),
            args.optString("grep").takeIf { it.isNotBlank() },
        ).toString(),
    )
}

private fun McpToolRegistry.runLogJson(
    record: RunLogRecord,
    tail: Int,
    level: LogLevel?,
    grep: String?,
): JSONObject {
    val logs = JSONArray()
    RunLogRegistry.filter(record, tail, level, grep).forEach { entry ->
        logs.put(
            JSONObject()
                .put("time", entry.timeMillis)
                .put("level", entry.level.name.lowercase())
                .put("message", entry.message),
        )
    }
    return JSONObject().apply {
        put("ok", record.status != RunStatus.FAILED && record.status != RunStatus.TIMEOUT)
        put("run_id", record.runId)
        put("script_id", record.scriptId)
        put("script", record.scriptName)
        put("status", record.status.name.lowercase())
        put("startedAt", record.startedAt)
        if (record.finishedAt > 0L) put("finishedAt", record.finishedAt)
        put(
            "elapsed_ms",
            if (record.finishedAt > 0L) record.finishedAt - record.startedAt
            else System.currentTimeMillis() - record.startedAt,
        )
        record.error?.let { put("error", it) }
        put("logs", logs)
    }
}

private fun McpToolRegistry.parseLogLevel(raw: String): LogLevel? =
    raw.trim().takeIf { it.isNotBlank() }?.let { value ->
        LogLevel.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }

// ---- 异步结果查询（绕过设备队列，直接执行） ----

internal fun McpToolRegistry.getResultTool(): McpTool = McpTool(
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

internal fun McpToolRegistry.listOperationsTool(): McpTool = McpTool(
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

private fun McpToolRegistry.operationJson(op: OperationState): String = JSONObject().apply {
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
