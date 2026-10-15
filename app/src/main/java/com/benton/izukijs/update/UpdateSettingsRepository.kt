package com.benton.izukijs.update

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UpdateMethod { IN_APP, GITHUB }

data class UpdateSettings(
    val autoCheck: Boolean = true,
    val method: UpdateMethod = UpdateMethod.IN_APP,
)

class UpdateSettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<UpdateSettings> = _settings.asStateFlow()

    fun current(): UpdateSettings = _settings.value

    fun save(value: UpdateSettings) {
        _settings.value = value
        prefs.edit()
            .putBoolean(KEY_AUTO, value.autoCheck)
            .putString(KEY_METHOD, value.method.name)
            .apply()
    }

    private fun load(): UpdateSettings = UpdateSettings(
        autoCheck = prefs.getBoolean(KEY_AUTO, true),
        method = runCatching { UpdateMethod.valueOf(prefs.getString(KEY_METHOD, null).orEmpty()) }
            .getOrDefault(UpdateMethod.IN_APP),
    )

    private companion object {
        const val PREFS = "izukijs_update"
        const val KEY_AUTO = "auto_check"
        const val KEY_METHOD = "method"
    }
}
