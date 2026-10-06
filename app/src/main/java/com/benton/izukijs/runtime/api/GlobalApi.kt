package com.benton.izukijs.runtime.api

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.benton.izukijs.runtime.LogBus
import com.benton.izukijs.runtime.ScriptExitException
import com.benton.izukijs.service.CaptureSettings
import com.benton.izukijs.service.scaledByPercent
import java.io.File
import java.io.FileOutputStream

/**
 * 全局 API：toast / log / sleep / exit / captureScreen。
 * 通过 [com.quickjs.JSContext.appendJavascriptInterface] 直接挂到全局作用域。
 */
class GlobalApi(
    private val context: Context,
    private val logBus: LogBus,
    private val screenshotProvider: () -> Bitmap?,
    private val captureSettingsProvider: () -> CaptureSettings,
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

    @JavascriptInterface
    fun sleep(millis: Double) {
        var remaining = millis.toLong()
        while (remaining > 0L) {
            if (isExitRequested()) throw ScriptExitException()
            val step = minOf(remaining, SLEEP_SLICE_MS)
            Thread.sleep(step)
            remaining -= step
        }
    }

    @JavascriptInterface
    fun exit() {
        onExit()
        throw ScriptExitException()
    }

    @JavascriptInterface
    fun captureScreen(path: String): String? {
        val source = screenshotProvider() ?: return null
        val settings = captureSettingsProvider()
        val bitmap = source.scaledByPercent(settings.scalePercent)
        return try {
            val file = if (path.isBlank()) {
                File(context.cacheDir, "screenshot_${System.currentTimeMillis()}.png")
            } else {
                File(path)
            }
            file.parentFile?.mkdirs()
            val format = if (file.name.endsWith(".jpg", true) || file.name.endsWith(".jpeg", true)) {
                Bitmap.CompressFormat.JPEG
            } else {
                Bitmap.CompressFormat.PNG
            }
            FileOutputStream(file).use { out ->
                bitmap.compress(format, settings.jpegQuality, out)
            }
            file.absolutePath
        } catch (t: Throwable) {
            null
        } finally {
            if (bitmap !== source) bitmap.recycle()
            source.recycle()
        }
    }

    private companion object {
        const val SLEEP_SLICE_MS = 40L
    }
}
