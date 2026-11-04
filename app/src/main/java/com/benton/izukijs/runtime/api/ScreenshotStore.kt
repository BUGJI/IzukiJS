package com.benton.izukijs.runtime.api

import android.content.Context
import android.graphics.Bitmap
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.service.CaptureSettings
import com.benton.izukijs.service.scaledByPercent
import java.io.File
import java.io.FileOutputStream

/**
 * 截图落盘。调用方把 Bitmap 交给这里后不再负责 recycle。
 */
class ScreenshotStore(
    private val context: Context,
    private val settingsProvider: () -> CaptureSettings,
    private val logBus: LogBus,
) {

    fun save(source: Bitmap, path: String): String? {
        val settings = settingsProvider()
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
            logBus.debug("保存截图 → ${file.absolutePath} (${bitmap.width}×${bitmap.height})")
            file.absolutePath
        } catch (t: Throwable) {
            logBus.warn("保存截图失败：${t.message}")
            null
        } finally {
            if (bitmap !== source) bitmap.recycle()
            source.recycle()
        }
    }
}
