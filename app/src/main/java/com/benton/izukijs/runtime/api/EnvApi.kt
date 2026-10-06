package com.benton.izukijs.runtime.api

import android.webkit.JavascriptInterface
import com.benton.izukijs.runtime.ScriptEnv
import org.json.JSONObject

/**
 * 运行参数只读访问。挂在全局 `env`，由脚本头部 `// @env` 声明、运行前写入。
 *
 * QuickJS 的接口方法要求参数个数与调用完全一致，因此带可选参数的 `env.get` / `env.all`
 * 在 prelude 中基于下面的 Raw 方法包装。
 */
class EnvApi(private val env: ScriptEnv) {

    @JavascriptInterface
    fun getRaw(key: String): String? = env.value(key)

    @JavascriptInterface
    fun getOrRaw(key: String, fallback: String): String = env.value(key) ?: fallback

    @JavascriptInterface
    fun has(key: String): Boolean = env.hasValue(key)

    /** 便捷取运行模式：先找 `MODE`，再找 `mode`。 */
    @JavascriptInterface
    fun mode(): String? = env.value("MODE") ?: env.value("mode")

    @JavascriptInterface
    fun allRaw(): String = JSONObject(env.allValues()).toString()
}
