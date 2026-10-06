package com.benton.izukijs.runtime.api

import android.webkit.JavascriptInterface
import com.benton.izukijs.runtime.LogBus

/**
 * 控制台 API。挂在全局 `console` 命名空间。
 */
class ConsoleApi(private val logBus: LogBus) {

    @JavascriptInterface
    fun log(message: String) = logBus.info(message)

    @JavascriptInterface
    fun info(message: String) = logBus.info(message)

    @JavascriptInterface
    fun debug(message: String) = logBus.debug(message)

    @JavascriptInterface
    fun warn(message: String) = logBus.warn(message)

    @JavascriptInterface
    fun error(message: String) = logBus.error(message)
}
