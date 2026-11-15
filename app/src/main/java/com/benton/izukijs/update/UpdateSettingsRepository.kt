package com.benton.izukijs.update

import android.content.Context
import android.content.SharedPreferences
import com.benton.izukijs.core.prefs.PrefsStateStore
import kotlinx.coroutines.flow.StateFlow

enum class UpdateMethod { IN_APP, GITHUB }

data class UpdateSettings(
    val autoCheck: Boolean = true,
    val method: UpdateMethod = UpdateMethod.IN_APP,
    val wifiOnly: Boolean = true,
)

class UpdateSettingsRepository(context: Context) : PrefsStateStore<UpdateSettings>(
    context = context,
    prefsName = PREFS,
    defaultValue = UpdateSettings(),
) {

    val settings: StateFlow<UpdateSettings> get() = state

    override fun read(): UpdateSettings = UpdateSettings(
        autoCheck = prefs.getBoolean(KEY_AUTO, true),
        method = runCatching { UpdateMethod.valueOf(prefs.getString(KEY_METHOD, null).orEmpty()) }
            .getOrDefault(UpdateMethod.IN_APP),
        wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, true),
    )

    override fun write(editor: SharedPreferences.Editor, value: UpdateSettings) {
        editor
            .putBoolean(KEY_AUTO, value.autoCheck)
            .putString(KEY_METHOD, value.method.name)
            .putBoolean(KEY_WIFI_ONLY, value.wifiOnly)
    }

    private companion object {
        const val PREFS = "izukijs_update"
        const val KEY_AUTO = "auto_check"
        const val KEY_METHOD = "method"
        const val KEY_WIFI_ONLY = "wifi_only"
    }
}
