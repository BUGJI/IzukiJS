package com.benton.izukijs.data

import android.content.Context
import android.content.SharedPreferences
import com.benton.izukijs.core.prefs.PrefsStateStore
import kotlinx.coroutines.flow.StateFlow

/**
 * 快捷磁贴绑定：记录磁贴点击时要运行的脚本 id（null 表示未设置）。
 */
class TileSettingsRepository(context: Context) : PrefsStateStore<String?>(
    context = context,
    prefsName = PREFS,
    defaultValue = null,
) {

    val scriptId: StateFlow<String?> get() = state

    fun setScriptId(id: String?) = save(id)

    fun moveScriptId(oldId: String, newId: String) {
        if (oldId == newId || current() != oldId) return
        setScriptId(newId)
    }

    override fun read(): String? = prefs.getString(KEY_SCRIPT_ID, null)

    override fun write(editor: SharedPreferences.Editor, value: String?) {
        if (value == null) editor.remove(KEY_SCRIPT_ID) else editor.putString(KEY_SCRIPT_ID, value)
    }

    private companion object {
        const val PREFS = "izukijs_tile"
        const val KEY_SCRIPT_ID = "script_id"
    }
}
