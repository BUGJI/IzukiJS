package com.benton.izukijs.mcp.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具参数摘要：用于日志与审计的脱敏、截断。
 *
 * 只记录键名与有限的占位值，不把令牌、密码或长文本写进日志。
 */
internal object McpArgumentSummarizer {

    private const val ARG_MAX_CHARS = 80
    private const val LOG_ARGS_MAX_CHARS = 240

    private val SENSITIVE = listOf(
        "token",
        "password",
        "passwd",
        "secret",
        "apikey",
        "api_key",
        "authorization",
    )

    fun summarize(arguments: JSONObject): String {
        if (arguments.length() == 0) return ""
        val out = JSONObject()
        arguments.keys().forEach { key ->
            val value = arguments.opt(key)
            val lower = key.lowercase()
            out.put(
                key,
                when {
                    SENSITIVE.any { lower.contains(it) } -> "***"
                    key == "content" && value is String -> "<${value.length} chars>"
                    value is String -> value.take(ARG_MAX_CHARS)
                    value is JSONObject -> "{…}"
                    value is JSONArray -> "[${value.length()}]"
                    else -> value
                },
            )
        }
        return out.toString().let {
            if (it.length <= LOG_ARGS_MAX_CHARS) it else it.take(LOG_ARGS_MAX_CHARS) + "…"
        }
    }
}
