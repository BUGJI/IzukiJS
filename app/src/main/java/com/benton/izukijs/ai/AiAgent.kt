package com.benton.izukijs.ai

import com.benton.izukijs.runtime.LogBus
import org.json.JSONObject

/**
 * 无状态 Agent 循环。每次调用只处理一条用户输入，上下文由脚本作者自行拼接。
 *
 * 流程：system+user → 请求补全 → 若有工具调用则本地执行并回填 → 直到模型给出最终文本
 * 或达到 [AiConfig.maxSteps]。当服务不支持工具调用时自动降级为提示词协议。
 */
class AiAgent(
    private val config: AiConfig,
    private val client: AiClient,
    private val tools: AgentTools,
    private val logBus: LogBus,
    private val checkCancelled: () -> Unit,
) {

    fun run(
        userPrompt: String,
        maxStepsOverride: Int?,
        onDelta: ((String) -> Unit)?,
        onTool: ((String, String) -> Unit)?,
        onToolResult: ((String, String) -> Unit)?,
    ): String {
        val maxSteps = (maxStepsOverride ?: config.maxSteps).coerceIn(1, MAX_STEPS_LIMIT)
        val messages = ArrayList<ChatMessage>()
        messages.add(ChatMessage(role = "system", content = config.systemPrompt))
        messages.add(ChatMessage(role = "user", content = userPrompt))

        var useTools = config.toolsEnabled
        var steps = 0
        while (steps < maxSteps) {
            checkCancelled()
            steps++
            val specs = if (useTools) tools.specs() else emptyList()
            val result = try {
                logBus.info("🤖 AI 第 $steps/$maxSteps 步")
                client.chat(messages, specs, onDelta, checkCancelled)
            } catch (e: AiException) {
                if (useTools && e.httpCode in TOOLS_UNSUPPORTED_CODES) {
                    logBus.warn("服务不支持工具调用（HTTP ${e.httpCode}），切换提示词降级")
                    useTools = false
                    messages.add(0, ChatMessage(role = "system", content = AiPrompts.FALLBACK_PROTOCOL))
                    steps--
                    continue
                }
                throw e
            }

            if (useTools) {
                if (!result.wantsTools) return result.content
                messages.add(
                    ChatMessage(role = "assistant", content = result.content, toolCalls = result.toolCalls),
                )
                for (call in result.toolCalls) {
                    checkCancelled()
                    onTool?.invoke(call.name, call.arguments)
                    val output = tools.execute(call.name, call.arguments)
                    onToolResult?.invoke(call.name, output)
                    messages.add(ChatMessage(role = "tool", content = output, toolCallId = call.id))
                }
            } else {
                val fallback = parseFallbackTool(result.content)
                if (fallback == null) return result.content
                messages.add(ChatMessage(role = "assistant", content = result.content))
                onTool?.invoke(fallback.name, fallback.arguments)
                val output = tools.execute(fallback.name, fallback.arguments)
                onToolResult?.invoke(fallback.name, output)
                messages.add(ChatMessage(role = "user", content = "工具结果：$output"))
            }
        }

        logBus.warn("AI 已达到最大步数 $maxSteps")
        return "（已达到最大步数 $maxSteps，未能完成脱困。请人工确认界面状态）"
    }

    /** 从降级协议的正文中解析一次工具调用。 */
    private fun parseFallbackTool(content: String): ToolCall? {
        val tagIndex = content.indexOf(AiPrompts.TOOL_TAG)
        if (tagIndex < 0) return null
        val payload = content.substring(tagIndex + AiPrompts.TOOL_TAG.length)
        val start = payload.indexOf('{')
        val end = payload.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val obj = JSONObject(payload.substring(start, end + 1))
            val name = obj.optString("name")
            if (name.isBlank()) return@runCatching null
            val arguments = obj.optJSONObject("arguments")?.toString() ?: "{}"
            ToolCall(id = "fallback_${name}_${System.nanoTime()}", name = name, arguments = arguments)
        }.getOrNull()
    }

    private companion object {
        const val MAX_STEPS_LIMIT = 50
        val TOOLS_UNSUPPORTED_CODES = setOf(400, 404, 422)
    }
}
