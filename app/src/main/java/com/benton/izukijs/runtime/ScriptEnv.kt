package com.benton.izukijs.runtime

import com.benton.izukijs.data.ScriptEnvRepository

/**
 * 单次脚本运行的环境：只读输入参数 + 可写运行状态。
 *
 * 状态经 [ScriptEnvRepository] 持久化，可跨次运行保留。
 */
class ScriptEnv(
    val scriptId: String,
    private val values: Map<String, String>,
    private val repository: ScriptEnvRepository,
) {
    private val state = LinkedHashMap<String, String>(repository.state(scriptId))

    // ---- 输入参数 ----

    fun value(key: String): String? = values[key]

    fun allValues(): Map<String, String> = values

    fun hasValue(key: String): Boolean = values.containsKey(key)

    // ---- 运行状态 ----

    fun stateGet(key: String): String? = state[key]

    fun stateAll(): Map<String, String> = state.toMap()

    fun stateSet(key: String, value: String) {
        state[key] = value
        repository.putState(scriptId, key, value)
    }

    fun stateRemove(key: String) {
        state.remove(key)
        repository.removeState(scriptId, key)
    }

    fun stateClear() {
        state.clear()
        repository.clearState(scriptId)
    }
}
