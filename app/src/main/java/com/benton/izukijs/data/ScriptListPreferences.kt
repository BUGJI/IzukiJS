package com.benton.izukijs.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 脚本列表排序方式。 */
enum class ScriptSort { UPDATED, NAME }

/**
 * 脚本列表的展示偏好：置顶（收藏）集合与排序方式，持久化到 SharedPreferences。
 */
class ScriptListPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _pinned = MutableStateFlow(
        prefs.getStringSet(KEY_PINNED, null)?.toSet() ?: emptySet(),
    )
    val pinned: StateFlow<Set<String>> = _pinned.asStateFlow()

    private val _sort = MutableStateFlow(
        runCatching { ScriptSort.valueOf(prefs.getString(KEY_SORT, null).orEmpty()) }
            .getOrDefault(ScriptSort.UPDATED),
    )
    val sort: StateFlow<ScriptSort> = _sort.asStateFlow()

    fun togglePinned(id: String) {
        val next = if (id in _pinned.value) _pinned.value - id else _pinned.value + id
        _pinned.value = next
        prefs.edit().putStringSet(KEY_PINNED, next).apply()
    }

    fun setSort(sort: ScriptSort) {
        _sort.value = sort
        prefs.edit().putString(KEY_SORT, sort.name).apply()
    }

    private companion object {
        const val PREFS = "izukijs_script_list"
        const val KEY_PINNED = "pinned"
        const val KEY_SORT = "sort"
    }
}
