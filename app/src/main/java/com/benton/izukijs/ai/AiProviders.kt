package com.benton.izukijs.ai

import androidx.annotation.StringRes
import com.benton.izukijs.R

/**
 * 常见 OpenAI 兼容服务商的 Base URL / 模型预设。
 *
 * 只用于填表，不涉及密钥；服务商地址可能调整，集中在这里便于一处修正。
 */
data class AiProviderPreset(
    val id: String,
    @StringRes val labelRes: Int,
    val baseUrl: String,
    val model: String,
)

object AiProviders {

    val presets = listOf(
        AiProviderPreset(
            id = "openai",
            labelRes = R.string.ai_provider_openai,
            baseUrl = AiConfig.DEFAULT_BASE_URL,
            model = AiConfig.DEFAULT_MODEL,
        ),
        AiProviderPreset(
            id = "deepseek",
            labelRes = R.string.ai_provider_deepseek,
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
        ),
        AiProviderPreset(
            id = "qwen",
            labelRes = R.string.ai_provider_qwen,
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            model = "qwen-plus",
        ),
        AiProviderPreset(
            id = "ollama",
            labelRes = R.string.ai_provider_ollama,
            baseUrl = "http://127.0.0.1:11434/v1",
            model = "llama3.1",
        ),
    )

    /** 按 Base URL 精确匹配预设；已手填自定义地址时返回 null。 */
    fun match(baseUrl: String): AiProviderPreset? {
        val target = normalize(baseUrl)
        return presets.firstOrNull { normalize(it.baseUrl) == target }
    }

    private fun normalize(url: String): String = url.trim().trimEnd('/')
}
