package com.benton.izukijs.runtime

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 脚本网络（HTTP）开关与域名白名单。
 *
 * 默认关闭。白名单为空表示允许任意 http/https 主机；填写后按「主机名相等或为其子域」
 * 匹配，支持逗号 / 分号 / 换行分隔，也接受 `*.example.com` 形式。
 */
data class HttpSettings(
    val enabled: Boolean = false,
    val allowlist: String = "",
)

class HttpSettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<HttpSettings> = _settings.asStateFlow()

    fun current(): HttpSettings = _settings.value

    fun save(value: HttpSettings) {
        _settings.value = value
        prefs.edit()
            .putBoolean(KEY_ENABLED, value.enabled)
            .putString(KEY_ALLOWLIST, value.allowlist)
            .apply()
    }

    private fun load(): HttpSettings = HttpSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        allowlist = prefs.getString(KEY_ALLOWLIST, "").orEmpty(),
    )

    private companion object {
        const val PREFS = "izukijs_http"
        const val KEY_ENABLED = "enabled"
        const val KEY_ALLOWLIST = "allowlist"
    }
}

/**
 * 判断主机是否命中白名单。白名单为空时放行；否则主机必须等于条目或为其子域。
 * 抽成纯函数便于单测。
 */
internal fun isHttpHostAllowed(host: String, allowlist: String): Boolean {
    val target = normalizeHost(host)
    if (target.isEmpty()) return false
    val entries = allowlist
        .split(',', ';', '\n', '\r', ' ', '\t')
        .map { normalizeAllowlistEntry(it) }
        .filter { it.isNotEmpty() }
    if (entries.isEmpty()) return true
    return entries.any { entry -> target == entry || target.endsWith(".$entry") }
}

private fun normalizeHost(raw: String): String = raw.trim().trimEnd('.').lowercase()

private fun normalizeAllowlistEntry(raw: String): String {
    var value = raw.trim().lowercase()
    if (value.isEmpty()) return ""
    value = value.removePrefix("http://").removePrefix("https://")
    value = value.substringBefore('/').substringBefore('?').substringBefore('#')
    value = value.substringBefore(':')
    value = value.removePrefix("*.").removePrefix(".")
    return value.trimEnd('.')
}
