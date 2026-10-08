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
import java.io.File
import java.util.Locale
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
        val screen = screenshot() ?: run {
            logBus.warn("找图失败：无法获取屏幕截图")
            return null
        }
        return try {
            matchTemplate(screen, templatePath, threshold)
        } finally {
            screen.recycle()
        }
    }

    @JavascriptInterface
    fun findImageInRaw(screenPath: String, templatePath: String, threshold: Double): String? {
        val screen = android.graphics.BitmapFactory.decodeFile(screenPath) ?: run {
            logBus.warn("找图失败：无法读取屏幕图片 $screenPath")
            return null
        }
        return try {
            matchTemplate(screen, templatePath, threshold)
        } finally {
            screen.recycle()
        }
    }

    @JavascriptInterface
    fun findColorRaw(color: String, threshold: Double): String? {
        val target = parseColor(color) ?: run {
            logBus.warn("找色失败：颜色格式无效 \"$color\"")
            return null
        }
        val bitmap = screenshot() ?: run {
            logBus.warn("找色失败：无法获取屏幕截图")
            return null
        }
        return try {
            val raw = findColorInBitmap(bitmap, target, threshold.toInt())
            if (raw == null) {
                logBus.debug("找色 $color (±${threshold.toInt()}) → 未找到 ✗")
            } else {
                logBus.debug("找色 $color (±${threshold.toInt()}) → ($raw) ✓")
            }
            raw
        } finally {
            bitmap.recycle()
        }
    }

    @JavascriptInterface
    fun available(): Boolean = ensureOpenCv()

    /** 复用的整屏像素缓冲，避免每次找色都分配数 MB 的 IntArray。 */
    private var colorPixels = IntArray(0)

    private fun matchTemplate(screen: Bitmap, templatePath: String, threshold: Double): String? {
        if (!ensureOpenCv()) return null
        // 模板会被缓存并复用，屏幕直接保持 RGBA 四通道；只需转换体积很小的模板，
        // 省掉每次调用对整屏做 RGBA→RGB 的转换。
        val label = File(templatePath).name
        val template = loadTemplate(templatePath) ?: run {
            logBus.warn("找图失败：模板不存在或无法读取 $templatePath")
            return null
        }
        var screenMat: Mat? = null
        var result: Mat? = null
        return try {
            screenMat = Mat()
            Utils.bitmapToMat(screen, screenMat)

            if (template.cols() > screenMat.cols() || template.rows() > screenMat.rows()) {
                logBus.debug("找图 $label → 跳过：模板大于屏幕 ✗")
                return null
            }

            result = Mat()
            Imgproc.matchTemplate(screenMat, template, result, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(result)
            if (mm.maxVal < threshold) {
                logBus.debug(
                    "找图 $label → 未命中（最高 ${mm.maxVal.confidence()} < $threshold）✗",
                )
                return null
            }

            val centerX = mm.maxLoc.x + template.cols() / 2.0
            val centerY = mm.maxLoc.y + template.rows() / 2.0
            logBus.debug(
                "找图 $label → (${centerX.toInt()}, ${centerY.toInt()}) " +
                    "置信度 ${mm.maxVal.confidence()} ✓",
            )
            "${centerX.toInt()},${centerY.toInt()},${mm.maxVal}"
        } catch (t: Throwable) {
            logBus.error("图像匹配失败: ${t.message}")
            null
        } finally {
            screenMat?.release()
            result?.release()
        }
    }

    private fun Double.confidence(): String = String.format(Locale.US, "%.3f", this)

    /** 按「路径 + 修改时间」缓存解码后的模板（RGBA Mat）。返回的 Mat 归缓存所有，调用方不得释放。 */
    private fun loadTemplate(path: String): Mat? {
        TemplateCache.get(path)?.let { return it }
        var decoded: Mat? = null
        return try {
            decoded = Imgcodecs.imread(path, Imgcodecs.IMREAD_COLOR)
            if (decoded.empty()) return null
            val rgba = Mat()
            Imgproc.cvtColor(decoded, rgba, Imgproc.COLOR_BGR2RGBA)
            TemplateCache.put(path, rgba)
            rgba
        } catch (t: Throwable) {
            logBus.error("模板加载失败: ${t.message}")
            null
        } finally {
            decoded?.release()
        }
    }

    private fun findColorInBitmap(bitmap: Bitmap, target: Int, tolerance: Int): String? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val count = width * height
        if (colorPixels.size < count) colorPixels = IntArray(count)
        val pixels = colorPixels
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val tr = (target shr 16) and 0xFF
        val tg = (target shr 8) and 0xFF
        val tb = target and 0xFF
        val tol = tolerance.coerceIn(0, 255)
        for (i in 0 until count) {
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

    /** 进程级模板缓存：脚本多次运行、循环找图时复用已解码的模板，避免反复读盘解码。 */
    private object TemplateCache {
        private const val MAX_ENTRIES = 8
        private val lock = Any()
        private val entries = LinkedHashMap<String, Entry>()

        private class Entry(val lastModified: Long, val mat: Mat)

        fun get(path: String): Mat? = synchronized(lock) {
            val entry = entries[path]
            when {
                entry == null -> null
                entry.lastModified != File(path).lastModified() -> {
                    entries.remove(path)
                    entry.mat.release()
                    null
                }
                else -> entry.mat
            }
        }

        fun put(path: String, mat: Mat) {
            synchronized(lock) {
                entries[path]?.mat?.release()
                entries[path] = Entry(File(path).lastModified(), mat)
                if (entries.size > MAX_ENTRIES) {
                    val iterator = entries.entries.iterator()
                    if (iterator.hasNext()) {
                        val eldest = iterator.next()
                        iterator.remove()
                        eldest.value.mat.release()
                    }
                }
            }
        }
    }
}
