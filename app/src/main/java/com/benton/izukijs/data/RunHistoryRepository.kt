package com.benton.izukijs.data

import android.content.Context
import com.benton.izukijs.model.RunRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 最近的脚本运行历史（按运行时间倒序、去重），持久化到 SharedPreferences。
 * 供运行页做「快速启动」；进程重启后依然保留。
 */
class RunHistoryRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _recent = MutableStateFlow(load())
    val recent: StateFlow<List<RunRecord>> = _recent.asStateFlow()

    /** 记录一次运行，把脚本移到最前，最多保留 [MAX] 条。以 id 去重，重命名后仍能命中。 */
    fun record(id: String, name: String) {
        if (id.isBlank() || name.isBlank()) return
        val next = buildList {
            add(RunRecord(id = id, name = name, at = System.currentTimeMillis()))
            // 同时清掉旧版本遗留的「只有名称」记录，避免同名脚本出现两条快捷入口。
            addAll(
                _recent.value
                    .filter { it.id != id && !(it.id.isBlank() && it.name == name) }
                    .take(MAX - 1),
            )
        }
        _recent.value = next
        persist(next)
    }

    fun clear() {
        if (_recent.value.isEmpty()) return
        _recent.value = emptyList()
        prefs.edit().remove(KEY).apply()
    }

    private fun load(): List<RunRecord> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val name = obj.optString("name")
                    // 旧版本只存名称；id 留空，由运行页回退到按名称匹配。
                    if (name.isNotBlank()) {
                        add(RunRecord(obj.optString("id"), name, obj.optLong("at")))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(list: List<RunRecord>) {
        val array = JSONArray()
        list.forEach { record ->
            array.put(
                JSONObject().apply {
                    put("id", record.id)
                    put("name", record.name)
                    put("at", record.at)
                },
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val PREFS = "izukijs_run_history"
        const val KEY = "recent"
        const val MAX = 8
    }
}
