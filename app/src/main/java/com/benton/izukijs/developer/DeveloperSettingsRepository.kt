package com.benton.izukijs.developer

import android.content.Context
import android.content.SharedPreferences
import com.benton.izukijs.core.prefs.PrefsStateStore
import kotlinx.coroutines.flow.StateFlow

/**
 * 开发者选项持久化。
 *
 * 参数值会随 [com.benton.izukijs.backup.ConfigSections] 导出 / 导入；[DeveloperSettings.unlocked]
 * 是本地入口可见性开关，不参与备份，导入与恢复默认都会保留当前状态。
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
