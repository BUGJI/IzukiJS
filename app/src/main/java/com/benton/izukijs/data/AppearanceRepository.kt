package com.benton.izukijs.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 主题模式。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 外观偏好：主题模式、是否跟随壁纸动态取色（Material You）、纯黑背景与自定义主题色。 */
data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** 深色主题下使用纯黑背景，省电（AMOLED 屏）。 */
    val amoledBackground: Boolean = false,
    /** 自定义种子色 ARGB；null 表示使用内置品牌色板。 */
    val seedArgb: Int? = null,
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
            .putBoolean(KEY_AMOLED, value.amoledBackground)
            .putInt(KEY_SEED, value.seedArgb ?: NO_SEED)
            .apply()
    }

    private fun load(): AppearanceSettings {
        val seed = prefs.getInt(KEY_SEED, NO_SEED)
        return AppearanceSettings(
            themeMode = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null).orEmpty()) }
                .getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC, true),
            amoledBackground = prefs.getBoolean(KEY_AMOLED, false),
            seedArgb = seed.takeIf { it != NO_SEED },
        )
    }

    private companion object {
        const val PREFS = "izukijs_appearance"
        const val KEY_THEME = "theme_mode"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_AMOLED = "amoled_background"
        const val KEY_SEED = "seed_argb"
        const val NO_SEED = -1
    }
}
