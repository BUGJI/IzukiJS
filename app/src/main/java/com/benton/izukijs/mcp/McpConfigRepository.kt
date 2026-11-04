package com.benton.izukijs.mcp

import android.content.Context
import android.content.SharedPreferences
import com.benton.izukijs.core.prefs.PrefsStateStore
import com.benton.izukijs.security.SecretCipher
import kotlinx.coroutines.flow.StateFlow

/**
 * MCP 服务配置持久化。访问令牌经 [SecretCipher] 加密落盘。
 */
class McpConfigRepository(context: Context) : PrefsStateStore<McpConfig>(
    context = context,
    prefsName = PREFS,
    defaultValue = McpConfig(),
) {

    val config: StateFlow<McpConfig> get() = state

    override fun read(): McpConfig {
        val mode = prefs.getString(KEY_BIND_MODE, null)
            ?.let { runCatching { McpBindMode.valueOf(it) }.getOrNull() }
            ?: McpBindMode.LOCALHOST
        return McpConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            port = prefs.getInt(KEY_PORT, McpConfig.DEFAULT_PORT),
            bindMode = mode,
            token = SecretCipher.decrypt(prefs.getString(KEY_TOKEN, "").orEmpty()),
            allowShell = prefs.getBoolean(KEY_ALLOW_SHELL, false),
            allowScripts = prefs.getBoolean(KEY_ALLOW_SCRIPTS, false),
            allowDangerousShell = prefs.getBoolean(KEY_ALLOW_DANGEROUS_SHELL, false),
            blockedPackages = prefs.getString(KEY_BLOCKED_PACKAGES, "")
                .orEmpty()
                .split('\n', ',')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
        )
    }

    override fun write(editor: SharedPreferences.Editor, value: McpConfig) {
        editor
            .putBoolean(KEY_ENABLED, value.enabled)
            .putInt(KEY_PORT, value.port)
            .putString(KEY_BIND_MODE, value.bindMode.name)
            .putString(KEY_TOKEN, SecretCipher.encrypt(value.token))
            .putBoolean(KEY_ALLOW_SHELL, value.allowShell)
            .putBoolean(KEY_ALLOW_SCRIPTS, value.allowScripts)
            .putBoolean(KEY_ALLOW_DANGEROUS_SHELL, value.allowDangerousShell)
            .putString(KEY_BLOCKED_PACKAGES, value.blockedPackages.joinToString("\n"))
    }

    private companion object {
        const val PREFS = "izukijs_mcp"
        const val KEY_ENABLED = "enabled"
        const val KEY_PORT = "port"
        const val KEY_BIND_MODE = "bind_mode"
        const val KEY_TOKEN = "token"
        const val KEY_ALLOW_SHELL = "allow_shell"
        const val KEY_ALLOW_SCRIPTS = "allow_scripts"
        const val KEY_ALLOW_DANGEROUS_SHELL = "allow_dangerous_shell"
        const val KEY_BLOCKED_PACKAGES = "blocked_packages"
    }
}
