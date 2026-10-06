package com.benton.izukijs.runtime.api

import android.webkit.JavascriptInterface

/**
 * 脚本模块加载：把某个脚本的源码交给 prelude 里的 `require` 在当前运行时内执行。
 *
 * 参数名会做规范化（去掉结尾的 `.js`、前缀 `./`），由调用方决定解析规则。
 */
class ModuleApi(private val sourceProvider: (String) -> String?) {

    @JavascriptInterface
    fun source(name: String): String? = sourceProvider(name)
}
