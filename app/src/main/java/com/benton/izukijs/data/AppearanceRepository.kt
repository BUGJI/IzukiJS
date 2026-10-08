package com.benton.izukijs.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 主题模式。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 外观偏好：主题模式与是否跟随壁纸动态取色（Material You）。 */
data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
)

/** SharedPreferences 持久化 + 内存缓存。 */
class AppearanceRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppearanceSettings> = _settings.asStateFlow()

    fun current(): AppearanceSettings = _settings.value

    fun save(value: AppearanceSettings) {
        _settings.value = value
        prefs.edit()
            .putString(KEY_THEME, value.themeMode.name)
            .putBoolean(KEY_DYNAMIC, value.dynamicColor)
            .apply()
    }

    private fun load(): AppearanceSettings = AppearanceSettings(
        themeMode = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null).orEmpty()) }
            .getOrDefault(ThemeMode.SYSTEM),
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC, true),
    )

    private companion object {
        const val PREFS = "izukijs_appearance"
        const val KEY_THEME = "theme_mode"
        const val KEY_DYNAMIC = "dynamic_color"
    }
}
