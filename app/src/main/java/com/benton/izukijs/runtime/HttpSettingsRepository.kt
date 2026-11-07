package com.benton.izukijs.runtime

import android.content.Context
import android.content.SharedPreferences
import com.benton.izukijs.core.net.HttpSettings
import com.benton.izukijs.core.prefs.PrefsStateStore
import kotlinx.coroutines.flow.StateFlow

/**
 * 脚本网络（HTTP）配置持久化。模型与白名单匹配逻辑见 [HttpSettings]。
 */
class HttpSettingsRepository(context: Context) : PrefsStateStore<HttpSettings>(
    context = context,
    prefsName = PREFS,
    defaultValue = HttpSettings(),
) {

    val settings: StateFlow<HttpSettings> get() = state

    override fun read(): HttpSettings = HttpSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        allowlist = prefs.getString(KEY_ALLOWLIST, "").orEmpty(),
    )

    override fun write(editor: SharedPreferences.Editor, value: HttpSettings) {
        editor
            .putBoolean(KEY_ENABLED, value.enabled)
            .putString(KEY_ALLOWLIST, value.allowlist)
    }

    private companion object {
        const val PREFS = "izukijs_http"
        const val KEY_ENABLED = "enabled"
        const val KEY_ALLOWLIST = "allowlist"
    }
}
