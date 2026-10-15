package com.benton.izukijs.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 快捷磁贴绑定：记录磁贴点击时要运行的脚本 id（null 表示未设置）。
 */
class TileSettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _scriptId = MutableStateFlow(prefs.getString(KEY_SCRIPT_ID, null))
    val scriptId: StateFlow<String?> = _scriptId.asStateFlow()

    fun current(): String? = _scriptId.value

    fun setScriptId(id: String?) {
        _scriptId.value = id
        prefs.edit().apply {
            if (id == null) remove(KEY_SCRIPT_ID) else putString(KEY_SCRIPT_ID, id)
        }.apply()
    }

    private companion object {
        const val PREFS = "izukijs_tile"
        const val KEY_SCRIPT_ID = "script_id"
    }
}
