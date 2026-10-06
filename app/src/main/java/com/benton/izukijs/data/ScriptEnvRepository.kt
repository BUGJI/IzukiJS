package com.benton.izukijs.data

import android.content.Context
import com.benton.izukijs.model.ScriptEnvData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * 脚本运行参数与运行状态的持久化。
 *
 * - `values`：运行前写入的输入参数（`// @env` 声明 + 用户覆盖）。
 * - `state`：脚本运行中通过 `state.set()` 写回的状态，可跨次运行。
 *
 * 以脚本 id（即文件名）为键，全部保存到单个 JSON 文件中。写入频率很低，直接整文件落盘。
 */
class ScriptEnvRepository(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()

    private val entries = LinkedHashMap<String, ScriptEnvData>()
    private val _data = MutableStateFlow<Map<String, ScriptEnvData>>(emptyMap())

    /** 供 UI 观察当前全部脚本的参数与状态。 */
    val data: StateFlow<Map<String, ScriptEnvData>> = _data.asStateFlow()

    init {
        synchronized(lock) {
            load()
            publish()
        }
    }

    fun values(scriptId: String): Map<String, String> = synchronized(lock) {
        entries[scriptId]?.values.orEmpty()
    }

    fun state(scriptId: String): Map<String, String> = synchronized(lock) {
        entries[scriptId]?.state.orEmpty()
    }

    /** 覆盖某个脚本的全部输入参数。 */
    fun saveValues(scriptId: String, values: Map<String, String>) = synchronized(lock) {
        val current = entries[scriptId] ?: ScriptEnvData()
        entries[scriptId] = current.copy(values = values)
        persist()
    }

    fun putState(scriptId: String, key: String, value: String) = synchronized(lock) {
        val current = entries[scriptId] ?: ScriptEnvData()
        entries[scriptId] = current.copy(state = current.state + (key to value))
        persist()
    }

    fun removeState(scriptId: String, key: String) = synchronized(lock) {
        val current = entries[scriptId] ?: return
        entries[scriptId] = current.copy(state = current.state - key)
        persist()
    }

    fun clearState(scriptId: String) = synchronized(lock) {
        val current = entries[scriptId] ?: return
        entries[scriptId] = current.copy(state = emptyMap())
        persist()
    }

    fun remove(scriptId: String) = synchronized(lock) {
        if (entries.remove(scriptId) != null) persist()
    }

    fun move(oldId: String, newId: String) = synchronized(lock) {
        val existing = entries.remove(oldId) ?: return
        entries[newId] = existing
        persist()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            root.keys().forEach { id ->
                val obj = root.optJSONObject(id) ?: return@forEach
                entries[id] = ScriptEnvData(
                    values = obj.optJSONObject("values").toStringMap(),
                    state = obj.optJSONObject("state").toStringMap(),
                )
            }
        }
    }

    private fun persist() {
        val root = JSONObject()
        entries.forEach { (id, entry) ->
            root.put(
                id,
                JSONObject().apply {
                    put("values", JSONObject(entry.values as Map<*, *>))
                    put("state", JSONObject(entry.state as Map<*, *>))
                },
            )
        }
        runCatching {
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
        publish()
    }

    private fun publish() {
        _data.value = entries.toMap()
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        val source = this ?: return emptyMap()
        return buildMap {
            source.keys().forEach { key -> put(key, source.optString(key, "")) }
        }
    }

    private companion object {
        const val FILE_NAME = "script_env.json"
    }
}
