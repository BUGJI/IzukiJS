package com.benton.izukijs.mcp.core

import android.content.Context
import com.benton.izukijs.mcp.protocol.McpAuditSink
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 操作审计日志：记录每次工具调用的时间、工具名、参数摘要与结果。
 *
 * 写入应用私有 JSONL 文件，自动截断；敏感键（token / password / secret / key / auth）
 * 在落盘前替换为 `***`，避免把访问令牌或密码写进日志。通过 `izuki://audit` 资源读取。
 */
class McpAuditLog internal constructor(private val file: File) : McpAuditSink {

    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    private val lock = Any()

    override fun record(
        tool: String,
        arguments: JSONObject,
        ok: Boolean,
        message: String?,
    ) {
        val entry = JSONObject().apply {
            put("time", System.currentTimeMillis())
            put("tool", tool)
            put("ok", ok)
            put("args", McpAuditSanitizer.sanitize(arguments, 0))
            message?.takeIf { it.isNotBlank() }?.let { put("message", it.take(500)) }
        }
        synchronized(lock) {
            runCatching {
                if (entry.toString().length > MAX_ENTRY_CHARS) {
                    entry.put("args", "…")
                }
                file.appendText(entry.toString() + "\n")
                trimLocked()
            }
        }
    }

    fun tail(maxLines: Int = 100): String = synchronized(lock) {
        runCatching {
            if (!file.exists()) return ""
            val lines = file.readLines()
            lines.takeLast(maxLines.coerceIn(1, MAX_LINES)).joinToString("\n")
        }.getOrDefault("")
    }

    private fun trimLocked() {
        if (file.length() <= MAX_BYTES) return
        runCatching {
            val lines = file.readLines().takeLast(MAX_LINES)
            file.writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }

    private companion object {
        const val FILE_NAME = "mcp_audit.jsonl"
        const val MAX_BYTES = 512L * 1024L
        const val MAX_LINES = 1000
        const val MAX_ENTRY_CHARS = 4000
    }
}

/**
 * 审计参数的递归脱敏与截断。独立成纯对象，便于在不构造 Android `Context` 的情况下测试。
 */
internal object McpAuditSanitizer {

    private const val MAX_DEPTH = 4
    private const val MAX_STRING = 500
    private const val MAX_ARRAY = 50

    private val SENSITIVE = listOf(
        "token",
        "password",
        "passwd",
        "secret",
        "apikey",
        "api_key",
        "authorization",
    )

    fun sanitize(value: Any?, depth: Int): Any? {
        if (depth > MAX_DEPTH) return "…"
        return when (value) {
            null, JSONObject.NULL -> JSONObject.NULL
            is JSONObject -> JSONObject().apply {
                value.keys().forEach { key ->
                    val lower = key.lowercase()
                    val sensitive = SENSITIVE.any { lower.contains(it) }
                    put(key, if (sensitive) "***" else sanitize(value.opt(key), depth + 1))
                }
            }

            is JSONArray -> JSONArray().apply {
                for (i in 0 until minOf(value.length(), MAX_ARRAY)) {
                    put(sanitize(value.opt(i), depth + 1))
                }
            }

            is String -> value.take(MAX_STRING)
            else -> value
        }
    }
}
