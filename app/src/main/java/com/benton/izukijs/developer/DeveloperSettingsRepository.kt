package com.benton.izukijs.developer

import android.content.Context
import android.content.SharedPreferences
import com.benton.izukijs.core.prefs.PrefsStateStore
import kotlinx.coroutines.flow.StateFlow

/**
 * 开发者选项持久化。
 *
 * 不注册到 [com.benton.izukijs.backup.ConfigSections]，因此不会随备份导出 / 导入，
 * 「备份与恢复」页的恢复默认也不会清空这里的值。
 */
class DeveloperSettingsRepository(context: Context) : PrefsStateStore<DeveloperSettings>(
    context = context,
    prefsName = PREFS,
    defaultValue = DeveloperSettings(),
) {

    val settings: StateFlow<DeveloperSettings> get() = state

    override fun read(): DeveloperSettings {
        val base = DeveloperSettings(unlocked = prefs.getBoolean(KEY_UNLOCKED, false))
        return DeveloperSettingSpecs.all
            .fold(base) { acc, spec -> spec.set(acc, prefs.getInt(spec.key, spec.default)) }
            .normalized()
    }

    override fun write(editor: SharedPreferences.Editor, value: DeveloperSettings) {
        editor.putBoolean(KEY_UNLOCKED, value.unlocked)
        DeveloperSettingSpecs.all.forEach { spec ->
            editor.putInt(spec.key, spec.get(value))
        }
    }

    override fun normalize(value: DeveloperSettings): DeveloperSettings = value.normalized()

    private companion object {
        const val PREFS = "izukijs_developer"
        const val KEY_UNLOCKED = "developer_unlocked"
    }
}
