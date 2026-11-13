package com.benton.izukijs.runtime.api

import android.webkit.JavascriptInterface
import com.benton.izukijs.core.log.LogBus

/**
 * 控制台 API。挂在全局 `console` 命名空间。
 *
 * [runId] 由脚本执行器显式注入：QuickJS 的 JS → Java 回调可能运行在不带协程上下文的
 * bridge 线程上，只靠 [com.benton.izukijs.core.log.RunLogRegistry] 的 ThreadLocal 会丢日志。
 */
class ConsoleApi(
    private val logBus: LogBus,
    private val runId: String? = null,
) {

    @JavascriptInterface
    fun log(message: String) = logBus.info(message, runId)

    @JavascriptInterface
    fun info(message: String) = logBus.info(message, runId)

    @JavascriptInterface
    fun debug(message: String) = logBus.debug(message, runId)

    @JavascriptInterface
    fun warn(message: String) = logBus.warn(message, runId)

    @JavascriptInterface
    fun error(message: String) = logBus.error(message, runId)
}
