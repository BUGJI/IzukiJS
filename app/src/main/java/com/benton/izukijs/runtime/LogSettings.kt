package com.benton.izukijs.runtime

import android.content.Context
import android.content.SharedPreferences
import com.benton.izukijs.core.log.LogLevel
import com.benton.izukijs.core.prefs.PrefsStateStore
import kotlinx.coroutines.flow.StateFlow

data class LogSettings(
    val autoClean: Boolean = true,
    val maxSizeMb: Int = 5,
    val retentionDays: Int = 7,
    /** 记录并持久化的最低日志级别，低于该级别的日志会被直接丢弃。 */
    val minLevel: LogLevel = LogLevel.DEBUG,
)

/**
 * 日志存储设置。SharedPreferences 持久化 + 内存缓存。
 */
class LogSettingsRepository(context: Context) : PrefsStateStore<LogSettings>(
    context = context,
    prefsName = PREFS,
    defaultValue = LogSettings(),
) {

    val settings: StateFlow<LogSettings> get() = state

    override fun read(): LogSettings = LogSettings(
        autoClean = prefs.getBoolean(KEY_AUTO_CLEAN, true),
        maxSizeMb = prefs.getInt(KEY_MAX_SIZE, 5),
        retentionDays = prefs.getInt(KEY_RETENTION, 7),
        minLevel = prefs.getString(KEY_MIN_LEVEL, null)
            ?.let { runCatching { LogLevel.valueOf(it) }.getOrNull() }
            ?: LogLevel.DEBUG,
    )

    override fun write(editor: SharedPreferences.Editor, value: LogSettings) {
        editor
            .putBoolean(KEY_AUTO_CLEAN, value.autoClean)
            .putInt(KEY_MAX_SIZE, value.maxSizeMb)
            .putInt(KEY_RETENTION, value.retentionDays)
            .putString(KEY_MIN_LEVEL, value.minLevel.name)
    }

    private companion object {
        const val PREFS = "izukijs_log"
        const val KEY_AUTO_CLEAN = "auto_clean"
        const val KEY_MAX_SIZE = "max_size_mb"
        const val KEY_RETENTION = "retention_days"
        const val KEY_MIN_LEVEL = "min_level"
    }
}
