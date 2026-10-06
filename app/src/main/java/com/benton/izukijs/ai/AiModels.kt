package com.benton.izukijs.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * AI Agent 全局配置。`apiKey` 与 `extraHeaderValue` 以密文形式落盘（见 SecretCipher）。
 *
 * 接口按 OpenAI Chat Completions 定义，`baseUrl` 换成兼容实现的地址即可对接
 * DeepSeek / 通义 / one-api / Ollama 等服务。
 */
data class AiConfig(
    val enabled: Boolean = false,
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val temperature: Double = 0.7,
    val maxTokens: Int = 2048,
    val timeoutSec: Int = 60,
    val stream: Boolean = true,
    val systemPrompt: String = AiPrompts.DEFAULT_AGENT_PROMPT,
    val maxSteps: Int = DEFAULT_MAX_STEPS,
    val toolsEnabled: Boolean = true,
    val allowShell: Boolean = false,
    val extraHeaderName: String = "",
    val extraHeaderValue: String = "",
) {
    val isConfigured: Boolean
        get() = enabled && apiKey.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()

    fun chatCompletionsUrl(): String = baseUrl.trim().trimEnd('/') + "/chat/completions"

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val DEFAULT_MAX_STEPS = 12
    }
}

/** 一条对话消息，序列化为 OpenAI 的 messages 元素。 */
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        if (content != null) {
            put("content", content)
        } else if (toolCalls.isEmpty()) {
            put("content", JSONObject.NULL)
        }
        if (toolCalls.isNotEmpty()) {
            put("tool_calls", JSONArray().apply {
                toolCalls.forEach { call ->
                    put(
                        JSONObject().apply {
                            put("id", call.id)
                            put("type", "function")
                            put(
                                "function",
                                JSONObject().apply {
                                    put("name", call.name)
                                    put("arguments", call.arguments)
                                },
                            )
                        },
                    )
                }
            })
        }
        if (toolCallId != null) put("tool_call_id", toolCallId)
    }
}

/** 模型要求调用的一次工具。 */
data class ToolCall(val id: String, val name: String, val arguments: String)

/** 暴露给模型的工具定义。 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JSONObject,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", "function")
        put(
            "function",
            JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", parameters)
            },
        )
    }
}

/** 单轮补全结果：正文 + 模型要求调用的工具列表。 */
data class ChatResult(val content: String, val toolCalls: List<ToolCall>) {
    val wantsTools: Boolean get() = toolCalls.isNotEmpty()
}
