package com.benton.izukijs.ai

import android.content.Context
import com.benton.izukijs.security.SecretCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AI Agent 配置持久化。敏感字段（apiKey / extraHeaderValue）经 [SecretCipher] 加密。
 */
class AiConfigRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<AiConfig> = _config.asStateFlow()

    fun current(): AiConfig = _config.value

    fun save(value: AiConfig) {
        _config.value = value
        prefs.edit()
            .putBoolean(KEY_ENABLED, value.enabled)
            .putString(KEY_BASE_URL, value.baseUrl)
            .putString(KEY_API_KEY, SecretCipher.encrypt(value.apiKey))
            .putString(KEY_MODEL, value.model)
            .putString(KEY_TEMPERATURE, value.temperature.toString())
            .putInt(KEY_MAX_TOKENS, value.maxTokens)
            .putInt(KEY_TIMEOUT, value.timeoutSec)
            .putBoolean(KEY_STREAM, value.stream)
            .putString(KEY_SYSTEM_PROMPT, value.systemPrompt)
            .putInt(KEY_MAX_STEPS, value.maxSteps)
            .putBoolean(KEY_TOOLS, value.toolsEnabled)
            .putBoolean(KEY_ALLOW_SHELL, value.allowShell)
            .putString(KEY_HEADER_NAME, value.extraHeaderName)
            .putString(KEY_HEADER_VALUE, SecretCipher.encrypt(value.extraHeaderValue))
            .apply()
    }

    private fun load(): AiConfig = AiConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        baseUrl = prefs.getString(KEY_BASE_URL, null) ?: AiConfig.DEFAULT_BASE_URL,
        apiKey = SecretCipher.decrypt(prefs.getString(KEY_API_KEY, "").orEmpty()),
        model = prefs.getString(KEY_MODEL, null) ?: AiConfig.DEFAULT_MODEL,
        temperature = prefs.getString(KEY_TEMPERATURE, null)?.toDoubleOrNull() ?: 0.7,
        maxTokens = prefs.getInt(KEY_MAX_TOKENS, 2048),
        timeoutSec = prefs.getInt(KEY_TIMEOUT, 60),
        stream = prefs.getBoolean(KEY_STREAM, true),
        systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, null) ?: AiPrompts.DEFAULT_AGENT_PROMPT,
        maxSteps = prefs.getInt(KEY_MAX_STEPS, AiConfig.DEFAULT_MAX_STEPS),
        toolsEnabled = prefs.getBoolean(KEY_TOOLS, true),
        allowShell = prefs.getBoolean(KEY_ALLOW_SHELL, false),
        extraHeaderName = prefs.getString(KEY_HEADER_NAME, "").orEmpty(),
        extraHeaderValue = SecretCipher.decrypt(prefs.getString(KEY_HEADER_VALUE, "").orEmpty()),
    )

    private companion object {
        const val PREFS = "izukijs_ai"
        const val KEY_ENABLED = "enabled"
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_TIMEOUT = "timeout_sec"
        const val KEY_STREAM = "stream"
        const val KEY_SYSTEM_PROMPT = "system_prompt"
        const val KEY_MAX_STEPS = "max_steps"
        const val KEY_TOOLS = "tools_enabled"
        const val KEY_ALLOW_SHELL = "allow_shell"
        const val KEY_HEADER_NAME = "header_name"
        const val KEY_HEADER_VALUE = "header_value"
    }
}
