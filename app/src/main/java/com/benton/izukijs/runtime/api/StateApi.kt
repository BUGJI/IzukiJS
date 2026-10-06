package com.benton.izukijs.runtime.api

import android.webkit.JavascriptInterface
import com.benton.izukijs.runtime.ScriptEnv
import org.json.JSONObject

/**
 * 运行状态读写。挂在全局 `state`，脚本可写回以改变自身状态，并跨次运行持久化。
 *
 * QuickJS 的接口方法要求参数个数与调用完全一致，因此带可选参数的 `state.get` / `state.all`
 * 在 prelude 中基于下面的 Raw 方法包装。
 */
class StateApi(private val env: ScriptEnv) {

    @JavascriptInterface
    fun getRaw(key: String): String? = env.stateGet(key)

    @JavascriptInterface
    fun getOrRaw(key: String, fallback: String): String = env.stateGet(key) ?: fallback

    @JavascriptInterface
    fun set(key: String, value: String) = env.stateSet(key, value)

    @JavascriptInterface
    fun has(key: String): Boolean = env.stateGet(key) != null

    @JavascriptInterface
    fun remove(key: String) = env.stateRemove(key)

    @JavascriptInterface
    fun clear() = env.stateClear()

    @JavascriptInterface
    fun allRaw(): String = JSONObject(env.stateAll()).toString()
}
