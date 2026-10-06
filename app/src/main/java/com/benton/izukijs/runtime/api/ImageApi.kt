package com.benton.izukijs.runtime.api

import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import com.benton.izukijs.runtime.LogBus
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * 图像匹配 API（OpenCV）。挂在全局 `images` 命名空间。
 *
 * 原生方法返回 "x,y[,confidence]" 形式的字符串，便于通过 JS 预置脚本包装成对象。
 */
class ImageApi(
    private val screenshotProvider: () -> Bitmap?,
    private val logBus: LogBus,
) {

    @JavascriptInterface
    fun findImageRaw(templatePath: String, threshold: Double): String? {
        val screen = screenshot() ?: return null
        return try {
            matchTemplate(screen, templatePath, threshold)
        } finally {
            screen.recycle()
        }
    }

    @JavascriptInterface
    fun findImageInRaw(screenPath: String, templatePath: String, threshold: Double): String? {
        val screen = android.graphics.BitmapFactory.decodeFile(screenPath) ?: return null
        return try {
            matchTemplate(screen, templatePath, threshold)
        } finally {
            screen.recycle()
        }
    }

    @JavascriptInterface
    fun findColorRaw(color: String, threshold: Double): String? {
        val target = parseColor(color) ?: return null
        val bitmap = screenshot() ?: return null
        return try {
            findColorInBitmap(bitmap, target, threshold.toInt())
        } finally {
            bitmap.recycle()
        }
    }

    @JavascriptInterface
    fun available(): Boolean = ensureOpenCv()

    private fun matchTemplate(screen: Bitmap, templatePath: String, threshold: Double): String? {
        if (!ensureOpenCv()) return null
        var screenMat: Mat? = null
        var screenRgb: Mat? = null
        var template: Mat? = null
        var templateRgb: Mat? = null
        var result: Mat? = null
        return try {
            screenMat = Mat()
            Utils.bitmapToMat(screen, screenMat)
            screenRgb = Mat()
            Imgproc.cvtColor(screenMat, screenRgb, Imgproc.COLOR_RGBA2RGB)

            template = Imgcodecs.imread(templatePath, Imgcodecs.IMREAD_COLOR)
            if (template.empty()) return null
            templateRgb = Mat()
            Imgproc.cvtColor(template, templateRgb, Imgproc.COLOR_BGR2RGB)

            if (templateRgb.cols() > screenRgb.cols() || templateRgb.rows() > screenRgb.rows()) {
                return null
            }

            result = Mat()
            Imgproc.matchTemplate(screenRgb, templateRgb, result, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(result)
            if (mm.maxVal < threshold) return null

            val centerX = mm.maxLoc.x + templateRgb.cols() / 2.0
            val centerY = mm.maxLoc.y + templateRgb.rows() / 2.0
            "${centerX.toInt()},${centerY.toInt()},${mm.maxVal}"
        } catch (t: Throwable) {
            logBus.error("图像匹配失败: ${t.message}")
            null
        } finally {
            screenMat?.release()
            screenRgb?.release()
            template?.release()
            templateRgb?.release()
            result?.release()
        }
    }

    private fun findColorInBitmap(bitmap: Bitmap, target: Int, tolerance: Int): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val tr = (target shr 16) and 0xFF
        val tg = (target shr 8) and 0xFF
        val tb = target and 0xFF
        val tol = tolerance.coerceIn(0, 255)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (abs(r - tr) <= tol && abs(g - tg) <= tol && abs(b - tb) <= tol) {
                return "${i % width},${i / width}"
            }
        }
        return null
    }

    private fun screenshot(): Bitmap? = screenshotProvider()

    private fun parseColor(color: String): Int? {
        val hex = color.trim().removePrefix("#")
        return try {
            when (hex.length) {
                6 -> (0xFF000000.toInt()) or hex.toInt(16)
                8 -> hex.toLong(16).toInt()
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun ensureOpenCv(): Boolean {
        if (openCvReady) return true
        synchronized(LOCK) {
            if (openCvReady) return true
            openCvReady = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)
            if (!openCvReady) logBus.error("OpenCV 初始化失败")
            return openCvReady
        }
    }

    private companion object {
        private val LOCK = Any()

        @Volatile
        private var openCvReady = false
    }
}
