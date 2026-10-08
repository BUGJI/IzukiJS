package com.benton.izukijs.mcp

import android.content.Context
import com.benton.izukijs.security.SecretCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MCP 服务配置持久化。访问令牌经 [SecretCipher] 加密落盘。
 */
class McpConfigRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<McpConfig> = _config.asStateFlow()

    fun current(): McpConfig = _config.value

    fun save(value: McpConfig) {
        _config.value = value
        prefs.edit()
            .putBoolean(KEY_ENABLED, value.enabled)
            .putInt(KEY_PORT, value.port)
            .putString(KEY_BIND_MODE, value.bindMode.name)
            .putString(KEY_TOKEN, SecretCipher.encrypt(value.token))
            .putBoolean(KEY_ALLOW_SHELL, value.allowShell)
            .putBoolean(KEY_ALLOW_SCRIPTS, value.allowScripts)
            .apply()
    }

    private fun load(): McpConfig {
        val mode = prefs.getString(KEY_BIND_MODE, null)
            ?.let { runCatching { McpBindMode.valueOf(it) }.getOrNull() }
            ?: McpBindMode.LAN
        return McpConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            port = prefs.getInt(KEY_PORT, McpConfig.DEFAULT_PORT),
            bindMode = mode,
            token = SecretCipher.decrypt(prefs.getString(KEY_TOKEN, "").orEmpty()),
            allowShell = prefs.getBoolean(KEY_ALLOW_SHELL, false),
            allowScripts = prefs.getBoolean(KEY_ALLOW_SCRIPTS, false),
        )
    }

    private companion object {
        const val PREFS = "izukijs_mcp"
        const val KEY_ENABLED = "enabled"
        const val KEY_PORT = "port"
        const val KEY_BIND_MODE = "bind_mode"
        const val KEY_TOKEN = "token"
        const val KEY_ALLOW_SHELL = "allow_shell"
        const val KEY_ALLOW_SCRIPTS = "allow_scripts"
    }
}
