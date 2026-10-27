package com.benton.izukijs.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * 全局环境变量。所有脚本共享，运行时可被脚本级变量覆盖。
 *
 * 与 [ScriptEnvRepository] 一样以整文件 JSON 落盘，写入频率低。
 */
class GlobalEnvRepository(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()

    private val _values = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 供设置页与脚本编辑页观察。 */
    val values: StateFlow<Map<String, String>> = _values.asStateFlow()

    init {
        synchronized(lock) {
            load()
            publish()
        }
    }

    fun current(): Map<String, String> = synchronized(lock) { _values.value }

    fun save(values: Map<String, String>) = synchronized(lock) {
        _values.value = LinkedHashMap(values)
        persist()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            val variables = root.optJSONObject("variables") ?: return
            _values.value = buildMap {
                variables.keys().forEach { key ->
                    put(key, variables.optString(key, ""))
                }
            }
        }
    }

    private fun persist() {
        val root = JSONObject().apply {
            put("variables", JSONObject(_values.value as Map<*, *>))
        }
        runCatching {
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    private fun publish() {
        _values.value = _values.value.toMap()
    }

    private companion object {
        const val FILE_NAME = "global_env.json"
    }
}
