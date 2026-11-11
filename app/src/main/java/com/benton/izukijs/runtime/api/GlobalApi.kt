package com.benton.izukijs.runtime.api

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.developer.DeveloperTuning
import com.benton.izukijs.virtualscreen.ScreenTarget

/**
 * 全局 API：toast / log / sleep / exit / captureScreen。
 * 通过 [com.quickjs.JSContext.appendJavascriptInterface] 直接挂到全局作用域。
 */
class GlobalApi(
    private val context: Context,
    private val logBus: LogBus,
    private val captureProvider: (ScreenTarget, Long) -> Bitmap?,
    private val screenshotStore: ScreenshotStore,
    private val onExit: () -> Unit,
    private val isExitRequested: () -> Boolean,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun toast(message: String) {
        mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    @JavascriptInterface
    fun log(message: String) = logBus.info(message)

    @JavascriptInterface
    fun debug(message: String) = logBus.debug(message)

    @JavascriptInterface
    fun warn(message: String) = logBus.warn(message)

    @JavascriptInterface
    fun error(message: String) = logBus.error(message)

    /**
     * 分片睡眠，便于响应停止请求。
     *
     * 注意：绝不能在这里抛出 Java 异常 —— QuickJS 的 @JavascriptInterface 回调会把异常
     * 包装成 RuntimeException 抛回 JNI，导致 ART 「No pending exception expected」直接 abort。
     * 因此这里只提前返回，由 prelude 中的 JS 包装函数检测 [shouldExit] 后抛出 JS 异常来终止脚本。
     */
    @JavascriptInterface
    fun sleep(millis: Double) {
        var remaining = millis.toLong()
        while (remaining > 0L) {
            if (isExitRequested()) return
            val step = minOf(remaining, SLEEP_SLICE_MS)
            Thread.sleep(step)
            remaining -= step
        }
    }

    /** 供 prelude 的 sleep/exit 包装函数查询是否已请求停止。 */
    @JavascriptInterface
    fun shouldExit(): Boolean = isExitRequested()

    @JavascriptInterface
    fun exit() {
        onExit()
    }

    @JavascriptInterface
    fun captureScreen(path: String): String? =
        captureScreenSource(path, ScreenTarget.AUTO.displayName())

    /** 指定捕获来源：auto / virtual / main。 */
    @JavascriptInterface
    fun captureScreenSource(path: String, source: String): String? {
        val target = ScreenTarget.from(source) ?: ScreenTarget.AUTO
        val bitmap = captureProvider(target, CAPTURE_WAIT_MS) ?: run {
            logBus.warn("保存截图失败：无法获取 ${target.displayName()} 屏幕截图")
            return null
        }
        return screenshotStore.save(bitmap, path)
    }

    private companion object {
        const val SLEEP_SLICE_MS = 40L
        val CAPTURE_WAIT_MS: Long get() = DeveloperTuning.current.captureWaitMs.toLong()
    }
}
