package com.benton.izukijs.ai

import com.benton.izukijs.runtime.LogBus
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** 网络/接口异常。`httpCode` 便于上层识别「不支持工具调用」并降级。 */
class AiException(message: String, val httpCode: Int = -1) : RuntimeException(message)

/**
 * OpenAI Chat Completions 客户端。使用 [HttpURLConnection]，不引入额外依赖。
 *
 * 支持完整 messages（system/user/assistant/tool）、tools/function calling、
 * 以及流式 SSE（逐字回调 [onDelta]）。
 */
class AiClient(
    private val config: AiConfig,
    private val logBus: LogBus,
) {

    /**
     * 发送一轮对话。流式模式下逐段回调 [onDelta]；[checkCancelled] 在读取过程中被调用，
     * 可抛出异常以中断（用于「停止脚本」）。
     */
    fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        onDelta: ((String) -> Unit)? = null,
        checkCancelled: (() -> Unit)? = null,
    ): ChatResult {
        val body = buildBody(messages, tools)
        val connection = openConnection(body)
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorBody = readAll(connection.errorStream)
                throw AiException("HTTP $code: ${errorBody.take(400)}", code)
            }
            return if (config.stream) {
                readStream(connection.inputStream, onDelta, checkCancelled)
            } else {
                readWhole(readAll(connection.inputStream))
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    // ---- 请求 ----

    private fun buildBody(messages: List<ChatMessage>, tools: List<ToolSpec>): JSONObject =
        JSONObject().apply {
            put("model", config.model)
            put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply { tools.forEach { put(it.toJson()) } })
                put("tool_choice", "auto")
            }
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
            put("stream", config.stream)
        }

    private fun openConnection(body: JSONObject): HttpURLConnection {
        val connection = (httpUrl(config.chatCompletionsUrl()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = config.timeoutSec * 1000
            readTimeout = config.timeoutSec * 1000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", if (config.stream) "text/event-stream" else "application/json")
            if (config.apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
            if (config.extraHeaderName.isNotBlank()) {
                setRequestProperty(config.extraHeaderName, config.extraHeaderValue)
            }
        }
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return connection
    }

    /** 校验协议，给出比类转换异常更可读的报错。 */
    private fun httpUrl(raw: String): URL {
        val url = URL(raw)
        val scheme = url.protocol.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw AiException("不支持的接口协议「$scheme」：baseUrl 需以 http:// 或 https:// 开头")
        }
        return url
    }

    // ---- 非流式 ----

    private fun readWhole(raw: String): ChatResult {
        val choices = JSONObject(raw).optJSONArray("choices")
        if (choices == null || choices.length() == 0) return ChatResult("", emptyList())
        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: return ChatResult("", emptyList())
        return ChatResult(
            content = message.optString("content").orEmpty(),
            toolCalls = parseToolCalls(message.optJSONArray("tool_calls")),
        )
    }

    private fun parseToolCalls(array: JSONArray?): List<ToolCall> {
        if (array == null) return emptyList()
        val calls = ArrayList<ToolCall>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val function = item.optJSONObject("function") ?: continue
            calls.add(
                ToolCall(
                    id = item.optString("id").ifBlank { "call_$i" },
                    name = function.optString("name"),
                    arguments = function.optString("arguments"),
                ),
            )
        }
        return calls
    }

    // ---- 流式 SSE ----

    private fun readStream(
        input: InputStream,
        onDelta: ((String) -> Unit)?,
        checkCancelled: (() -> Unit)?,
    ): ChatResult {
        val content = StringBuilder()
        val builders = LinkedHashMap<Int, ToolCallBuilder>()
        input.bufferedReader(Charsets.UTF_8).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                checkCancelled?.invoke()
                if (line.startsWith("data:")) {
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") break
                    if (data.isNotEmpty()) {
                        runCatching { parseChunk(data, content, builders, onDelta) }
                    }
                }
                line = reader.readLine()
            }
        }
        val toolCalls = builders.entries.sortedBy { it.key }.map { it.value.build() }
        return ChatResult(content.toString(), toolCalls)
    }

    private fun parseChunk(
        data: String,
        content: StringBuilder,
        builders: LinkedHashMap<Int, ToolCallBuilder>,
        onDelta: ((String) -> Unit)?,
    ) {
        val chunk = JSONObject(data)
        val choices = chunk.optJSONArray("choices") ?: return
        val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: return

        delta.optString("content", "").takeIf { it.isNotEmpty() }?.let {
            content.append(it)
            onDelta?.invoke(it)
        }

        val toolCalls = delta.optJSONArray("tool_calls") ?: return
        for (i in 0 until toolCalls.length()) {
            val item = toolCalls.optJSONObject(i) ?: continue
            val index = item.optInt("index", i)
            val builder = builders.getOrPut(index) { ToolCallBuilder() }
            item.optString("id").takeIf { it.isNotBlank() }?.let { builder.id = it }
            val function = item.optJSONObject("function") ?: continue
            function.optString("name").takeIf { it.isNotBlank() }?.let { builder.name = it }
            builder.arguments.append(function.optString("arguments"))
        }
    }

    private fun readAll(stream: InputStream?): String =
        runCatching { stream?.bufferedReader()?.use { it.readText() }.orEmpty() }.getOrDefault("")

    private class ToolCallBuilder {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()

        fun build(): ToolCall =
            ToolCall(id.ifBlank { "call_${hashCode()}" }, name, arguments.toString())
    }
}
