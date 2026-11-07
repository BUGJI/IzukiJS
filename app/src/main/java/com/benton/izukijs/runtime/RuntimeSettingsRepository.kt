package com.benton.izukijs.runtime

import android.content.Context
import android.content.SharedPreferences
import com.benton.izukijs.core.prefs.PrefsStateStore
import kotlinx.coroutines.flow.StateFlow

/**
 * 运行期开关。用于「权限与能力」页里那些无法程序化开关、或需要在应用层再做一层
 * 显式开关的能力：
 *
 * - [keepAwake] / [wakeScreen]：脚本运行期间是否持有唤醒锁、是否顺带亮屏（WAKE_LOCK）。
 * - [appList]：是否允许脚本枚举已安装应用（QUERY_ALL_PACKAGES 是安装期权限，用它当开关）。
 * - [progressNotification]：是否允许脚本通过 `notify.progress` 更新运行通知的进度样式。
 * - [tileEnabled]：快捷磁贴是否响应点击。
 * - [scriptTimeoutMinutes]：单次脚本运行的超时分钟数，0 表示不限制。
 * - [perfBaseline]：是否采集并定期输出性能基线（帧耗时、截图、找图找色、日志刷新等）。
 */
data class RuntimeSettings(
    val keepAwake: Boolean = true,
    val wakeScreen: Boolean = false,
    val appList: Boolean = true,
    val progressNotification: Boolean = true,
    val tileEnabled: Boolean = true,
    val scriptTimeoutMinutes: Int = 0,
    val perfBaseline: Boolean = false,
)

/** SharedPreferences 持久化 + 内存缓存。 */
class RuntimeSettingsRepository(context: Context) : PrefsStateStore<RuntimeSettings>(
    context = context,
    prefsName = PREFS,
    defaultValue = RuntimeSettings(),
) {

    val settings: StateFlow<RuntimeSettings> get() = state

    override fun read(): RuntimeSettings = RuntimeSettings(
        keepAwake = prefs.getBoolean(KEY_KEEP_AWAKE, true),
        wakeScreen = prefs.getBoolean(KEY_WAKE_SCREEN, false),
        appList = prefs.getBoolean(KEY_APP_LIST, true),
        progressNotification = prefs.getBoolean(KEY_PROGRESS_NOTIFICATION, true),
        tileEnabled = prefs.getBoolean(KEY_TILE, true),
        scriptTimeoutMinutes = prefs.getInt(KEY_SCRIPT_TIMEOUT, 0).coerceAtLeast(0),
        perfBaseline = prefs.getBoolean(KEY_PERF_BASELINE, false),
    )

    override fun write(editor: SharedPreferences.Editor, value: RuntimeSettings) {
        editor
            .putBoolean(KEY_KEEP_AWAKE, value.keepAwake)
            .putBoolean(KEY_WAKE_SCREEN, value.wakeScreen)
            .putBoolean(KEY_APP_LIST, value.appList)
            .putBoolean(KEY_PROGRESS_NOTIFICATION, value.progressNotification)
            .putBoolean(KEY_TILE, value.tileEnabled)
            .putInt(KEY_SCRIPT_TIMEOUT, value.scriptTimeoutMinutes)
            .putBoolean(KEY_PERF_BASELINE, value.perfBaseline)
    }

    private companion object {
        const val PREFS = "izukijs_runtime"
        const val KEY_KEEP_AWAKE = "keep_awake"
        const val KEY_WAKE_SCREEN = "wake_screen"
        const val KEY_APP_LIST = "app_list"
        const val KEY_PROGRESS_NOTIFICATION = "progress_notification"
        const val KEY_TILE = "tile_enabled"
        const val KEY_SCRIPT_TIMEOUT = "script_timeout_minutes"
        const val KEY_PERF_BASELINE = "perf_baseline"
    }
}
