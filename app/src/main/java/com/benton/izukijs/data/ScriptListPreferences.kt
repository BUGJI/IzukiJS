package com.benton.izukijs.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 脚本列表排序方式。 */
enum class ScriptSort { UPDATED, NAME }

/** 脚本列表分组方式。 */
enum class ScriptGroupMode { NONE, TAG }

/**
 * 脚本列表的展示偏好：置顶（收藏）集合、标签与排序方式，持久化到 SharedPreferences。
 */
class ScriptListPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _pinned = MutableStateFlow(
        prefs.getStringSet(KEY_PINNED, null)?.toSet() ?: emptySet(),
    )
    val pinned: StateFlow<Set<String>> = _pinned.asStateFlow()

    private val _tags = MutableStateFlow(loadTags())
    val tags: StateFlow<Map<String, List<String>>> = _tags.asStateFlow()

    private val _sort = MutableStateFlow(
        runCatching { ScriptSort.valueOf(prefs.getString(KEY_SORT, null).orEmpty()) }
            .getOrDefault(ScriptSort.UPDATED),
    )
    val sort: StateFlow<ScriptSort> = _sort.asStateFlow()

    private val _groupMode = MutableStateFlow(
        runCatching { ScriptGroupMode.valueOf(prefs.getString(KEY_GROUP_MODE, null).orEmpty()) }
            .getOrDefault(ScriptGroupMode.NONE),
    )
    val groupMode: StateFlow<ScriptGroupMode> = _groupMode.asStateFlow()

    fun togglePinned(id: String) {
        setPinned(id, id !in _pinned.value)
    }

    fun setPinned(id: String, pinned: Boolean) {
        val next = if (pinned) _pinned.value + id else _pinned.value - id
        if (next == _pinned.value) return
        _pinned.value = next
        prefs.edit().putStringSet(KEY_PINNED, next).apply()
    }

    fun movePinned(oldId: String, newId: String) {
        if (oldId == newId || oldId !in _pinned.value) return
        setPinned(oldId, false)
        setPinned(newId, true)
    }

    fun setSort(sort: ScriptSort) {
        _sort.value = sort
        prefs.edit().putString(KEY_SORT, sort.name).apply()
    }

    fun setGroupMode(mode: ScriptGroupMode) {
        _groupMode.value = mode
        prefs.edit().putString(KEY_GROUP_MODE, mode.name).apply()
    }

    fun setTags(id: String, tags: List<String>) {
        val normalized = normalizeTags(tags)
        val next = _tags.value.toMutableMap()
        if (normalized.isEmpty()) next.remove(id) else next[id] = normalized
        _tags.value = next
        persistTags()
    }

    fun removeTags(id: String) {
        if (id !in _tags.value) return
        _tags.value = _tags.value - id
        persistTags()
    }

    fun moveTags(oldId: String, newId: String) {
        val tags = _tags.value[oldId] ?: return
        _tags.value = _tags.value - oldId + (newId to tags)
        persistTags()
    }

    private fun loadTags(): Map<String, List<String>> {
        val raw = prefs.getString(KEY_TAGS, null)?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { id ->
                    val array = obj.optJSONArray(id) ?: return@forEach
                    val tags = buildList {
                        for (i in 0 until array.length()) {
                            array.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                        }
                    }
                    if (tags.isNotEmpty()) put(id, tags)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun persistTags() {
        val obj = JSONObject()
        _tags.value.forEach { (id, tags) ->
            obj.put(id, JSONArray().apply { tags.forEach { put(it) } })
        }
        prefs.edit().putString(KEY_TAGS, obj.toString()).apply()
    }

    private fun normalizeTags(raw: List<String>): List<String> = raw
        .map { it.trim().removePrefix("#").trim() }
        .filter { it.isNotEmpty() }
        .map { it.take(MAX_TAG_LENGTH) }
        .distinct()
        .take(MAX_TAGS)

    private companion object {
        const val PREFS = "izukijs_script_list"
        const val KEY_PINNED = "pinned"
        const val KEY_SORT = "sort"
        const val KEY_TAGS = "tags"
        const val KEY_GROUP_MODE = "group_mode"
        const val MAX_TAGS = 12
        const val MAX_TAG_LENGTH = 24
    }
}
