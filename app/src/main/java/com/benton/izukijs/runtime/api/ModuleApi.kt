package com.benton.izukijs.runtime.api

import android.webkit.JavascriptInterface
import com.benton.izukijs.data.ScriptModuleResolver

/**
 * 脚本模块加载：把解析结果交给 prelude 里的 `require` 在当前运行时内执行。
 *
 * `from` 为空时用当前脚本 id 作为起点；嵌套模块会传入上一个模块的绝对路径。
 */
class ModuleApi(
    private val resolver: ScriptModuleResolver,
    private val rootScriptId: String,
) {

    @JavascriptInterface
    fun source(name: String): String? = resolver.source(name, rootScriptId)

    @JavascriptInterface
    fun sourceFrom(name: String, from: String): String? =
        resolver.source(name, from.ifBlank { rootScriptId })

    @JavascriptInterface
    fun resolve(name: String, from: String): String? =
        resolver.resolve(name, from.ifBlank { rootScriptId })
}
