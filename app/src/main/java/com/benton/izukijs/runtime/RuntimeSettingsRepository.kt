package com.benton.izukijs.runtime

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 运行期开关。用于「权限与能力」页里那些无法程序化开关、或需要在应用层再做一层
 * 显式开关的能力：
 *
 * - [keepAwake] / [wakeScreen]：脚本运行期间是否持有唤醒锁、是否顺带亮屏（WAKE_LOCK）。
 * - [appList]：是否允许脚本枚举已安装应用（QUERY_ALL_PACKAGES 是安装期权限，用它当开关）。
 * - [tileEnabled]：快捷磁贴是否响应点击。
 * - [scriptTimeoutMinutes]：单次脚本运行的超时分钟数，0 表示不限制。
 */
data class RuntimeSettings(
    val keepAwake: Boolean = true,
    val wakeScreen: Boolean = false,
    val appList: Boolean = true,
    val tileEnabled: Boolean = true,
    val scriptTimeoutMinutes: Int = 0,
)

/** SharedPreferences 持久化 + 内存缓存。 */
class RuntimeSettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<RuntimeSettings> = _settings.asStateFlow()

    fun current(): RuntimeSettings = _settings.value

    fun save(value: RuntimeSettings) {
        _settings.value = value
        prefs.edit()
            .putBoolean(KEY_KEEP_AWAKE, value.keepAwake)
            .putBoolean(KEY_WAKE_SCREEN, value.wakeScreen)
            .putBoolean(KEY_APP_LIST, value.appList)
            .putBoolean(KEY_TILE, value.tileEnabled)
            .putInt(KEY_SCRIPT_TIMEOUT, value.scriptTimeoutMinutes)
            .apply()
    }

    private fun load(): RuntimeSettings = RuntimeSettings(
        keepAwake = prefs.getBoolean(KEY_KEEP_AWAKE, true),
        wakeScreen = prefs.getBoolean(KEY_WAKE_SCREEN, false),
        appList = prefs.getBoolean(KEY_APP_LIST, true),
        tileEnabled = prefs.getBoolean(KEY_TILE, true),
        scriptTimeoutMinutes = prefs.getInt(KEY_SCRIPT_TIMEOUT, 0).coerceAtLeast(0),
    )

    private companion object {
        const val PREFS = "izukijs_runtime"
        const val KEY_KEEP_AWAKE = "keep_awake"
        const val KEY_WAKE_SCREEN = "wake_screen"
        const val KEY_APP_LIST = "app_list"
        const val KEY_TILE = "tile_enabled"
        const val KEY_SCRIPT_TIMEOUT = "script_timeout_minutes"
    }
}
