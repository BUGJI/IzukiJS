package com.benton.izukijs.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 脚本编辑器偏好。 */
data class EditorSettings(
    /** 编辑器字号（sp）。 */
    val fontSizeSp: Int = 13,
    /** 停止输入后是否自动保存。关闭时仍会在返回 / 运行时保存。 */
    val autoSave: Boolean = true,
    /** 控制台抽屉高度（dp），由用户拖拽后记忆。 */
    val consoleHeightDp: Int = 240,
)

/** SharedPreferences 持久化 + 内存缓存。 */
class EditorSettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<EditorSettings> = _settings.asStateFlow()

    fun current(): EditorSettings = _settings.value

    fun save(value: EditorSettings) {
        _settings.value = value
        prefs.edit()
            .putInt(KEY_FONT_SIZE, value.fontSizeSp)
            .putBoolean(KEY_AUTO_SAVE, value.autoSave)
            .putInt(KEY_CONSOLE_HEIGHT, value.consoleHeightDp)
            .apply()
    }

    private fun load(): EditorSettings = EditorSettings(
        fontSizeSp = prefs.getInt(KEY_FONT_SIZE, 13),
        autoSave = prefs.getBoolean(KEY_AUTO_SAVE, true),
        consoleHeightDp = prefs.getInt(KEY_CONSOLE_HEIGHT, 240),
    )

    private companion object {
        const val PREFS = "izukijs_editor"
        const val KEY_FONT_SIZE = "font_size_sp"
        const val KEY_AUTO_SAVE = "auto_save"
        const val KEY_CONSOLE_HEIGHT = "console_height_dp"
    }
}
