package com.benton.izukijs.runtime.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.webkit.JavascriptInterface
import com.benton.izukijs.runtime.LogBus
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * 图像匹配 API（OpenCV）。挂在全局 `images` 命名空间。
 *
 * 原生方法返回 "x,y[,confidence]" 或 JSON 数组，便于通过 JS 预置脚本包装成对象。
 * 所有查找都支持可选的区域 `{x,y,width,height}`，坐标始终是屏幕像素。
 */
class ImageApi(
    private val context: Context,
    private val screenshotProvider: () -> Bitmap?,
    private val logBus: LogBus,
) {

    // ---- 找图 ----

    @JavascriptInterface
    fun findImageRaw(templatePath: String, threshold: Double): String? =
        findImageRegionRaw(templatePath, threshold, "")

    @JavascriptInterface
    fun findImageRegionRaw(
        templatePath: String,
        threshold: Double,
        regionJson: String,
    ): String? {
        val screen = screenshot() ?: run {
            logBus.warn("找图失败：无法获取屏幕截图")
            return null
        }
        return try {
            val region = parseRegion(regionJson, screen.width, screen.height, "找图") ?: return null
            val match = matchTemplate(screen, templatePath, threshold, region, 1).firstOrNull()
            if (match == null) {
                logBus.debug("找图 ${File(templatePath).name} → 未命中 ✗")
                null
            } else {
                logBus.debug(
                    "找图 ${File(templatePath).name} → (${match.x}, ${match.y}) " +
                        "置信度 ${match.confidence.confidence()} ✓",
                )
                "${match.x},${match.y},${match.confidence}"
            }
        } finally {
            screen.recycle()
        }
    }

    @JavascriptInterface
    fun findAllImagesRaw(
        templatePath: String,
        threshold: Double,
        regionJson: String,
        limit: Int,
    ): String {
        val screen = screenshot() ?: run {
            logBus.warn("找图失败：无法获取屏幕截图")
            return "[]"
        }
        return try {
            val region = parseRegion(regionJson, screen.width, screen.height, "找图") ?: return "[]"
            val matches = matchTemplate(screen, templatePath, threshold, region, limit)
            logBus.debug("找图 ${File(templatePath).name} → ${matches.size} 个结果")
            val array = JSONArray()
            matches.forEach { match ->
                array.put(
                    JSONObject().apply {
                        put("x", match.x)
                        put("y", match.y)
                        put("confidence", match.confidence)
                    },
                )
            }
            array.toString()
        } finally {
            screen.recycle()
        }
    }

    @JavascriptInterface
    fun findImageInRaw(screenPath: String, templatePath: String, threshold: Double): String? {
        val screen = BitmapFactory.decodeFile(screenPath) ?: run {
            logBus.warn("找图失败：无法读取屏幕图片 $screenPath")
            return null
        }
        return try {
            val match = matchTemplate(
                screen,
                templatePath,
                threshold,
                Rect(0, 0, screen.width, screen.height),
                1,
            ).firstOrNull() ?: return null
            "${match.x},${match.y},${match.confidence}"
        } finally {
            screen.recycle()
        }
    }

    // ---- 找色 ----

    @JavascriptInterface
    fun findColorRaw(color: String, threshold: Double): String? =
        findColorRegionRaw(color, threshold, "")

    @JavascriptInterface
    fun findColorRegionRaw(
        color: String,
        threshold: Double,
        regionJson: String,
    ): String? {
        val target = parseColor(color) ?: run {
            logBus.warn("找色失败：颜色格式无效 \"$color\"")
            return null
        }
        val bitmap = screenshot() ?: run {
            logBus.warn("找色失败：无法获取屏幕截图")
            return null
        }
        return try {
            val region = parseRegion(regionJson, bitmap.width, bitmap.height, "找色") ?: return null
            val match = findColorMatches(bitmap, target, threshold.toInt(), region, 1).firstOrNull()
            if (match == null) {
                logBus.debug("找色 $color (±${threshold.toInt()}) → 未找到 ✗")
                null
            } else {
                logBus.debug("找色 $color (±${threshold.toInt()}) → (${match.x},${match.y}) ✓")
                "${match.x},${match.y}"
            }
        } finally {
            bitmap.recycle()
        }
    }

    @JavascriptInterface
    fun findAllColorsRaw(
        color: String,
        threshold: Double,
        regionJson: String,
        limit: Int,
    ): String {
        val target = parseColor(color) ?: run {
            logBus.warn("找色失败：颜色格式无效 \"$color\"")
            return "[]"
        }
        val bitmap = screenshot() ?: run {
            logBus.warn("找色失败：无法获取屏幕截图")
            return "[]"
        }
        return try {
            val region = parseRegion(regionJson, bitmap.width, bitmap.height, "找色") ?: return "[]"
            val matches = findColorMatches(bitmap, target, threshold.toInt(), region, limit)
            logBus.debug("找色 $color (±${threshold.toInt()}) → ${matches.size} 个结果")
            val array = JSONArray()
            matches.forEach { match ->
                array.put(JSONObject().apply {
                    put("x", match.x)
                    put("y", match.y)
                })
            }
            array.toString()
        } finally {
            bitmap.recycle()
        }
    }

    /** 从当前屏幕裁剪一块区域保存为 PNG 模板；[path] 为空时保存到缓存目录。 */
    @JavascriptInterface
    fun saveTemplate(path: String, x: Int, y: Int, width: Int, height: Int): String? {
        val screen = screenshot() ?: run {
            logBus.warn("保存模板失败：无法获取屏幕截图")
            return null
        }
        return try {
            val rect = Rect(x, y, x + width, y + height)
            if (width <= 0 || height <= 0 || !rect.intersect(0, 0, screen.width, screen.height)) {
                logBus.warn("保存模板失败：区域无效 ($x,$y ${width}x$height)")
                return null
            }
            val crop = Bitmap.createBitmap(screen, rect.left, rect.top, rect.width(), rect.height())
            try {
                val file = if (path.isBlank()) {
                    File(context.cacheDir, "template_${System.currentTimeMillis()}.png")
                } else {
                    File(path)
                }
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { out ->
                    crop.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                logBus.debug("保存模板 → ${file.absolutePath} (${crop.width}×${crop.height})")
                file.absolutePath
            } finally {
                crop.recycle()
            }
        } catch (t: Throwable) {
            logBus.warn("保存模板失败：${t.message}")
            null
        } finally {
            screen.recycle()
        }
    }

    @JavascriptInterface
    fun available(): Boolean = ensureOpenCv()

    // ---- OpenCV ----

    private data class Match(val x: Int, val y: Int, val confidence: Double)

    private data class ColorMatch(val x: Int, val y: Int)

    private fun matchTemplate(
        screen: Bitmap,
        templatePath: String,
        threshold: Double,
        region: Rect,
        limit: Int,
    ): List<Match> {
        if (!ensureOpenCv()) return emptyList()
        val template = loadTemplate(templatePath) ?: run {
            logBus.warn("找图失败：模板不存在或无法读取 $templatePath")
            return emptyList()
        }
        val cropped = if (region.left == 0 &&
            region.top == 0 &&
            region.width() == screen.width &&
            region.height() == screen.height
        ) {
            screen
        } else {
            Bitmap.createBitmap(screen, region.left, region.top, region.width(), region.height())
        }
        var screenMat: Mat? = null
        var result: Mat? = null
        return try {
            if (template.cols() > cropped.width || template.rows() > cropped.height) {
                logBus.debug("找图 ${File(templatePath).name} → 跳过：模板大于查找区域 ✗")
                return emptyList()
            }
            screenMat = Mat()
            Utils.bitmapToMat(cropped, screenMat)
            result = Mat()
            Imgproc.matchTemplate(screenMat, template, result, Imgproc.TM_CCOEFF_NORMED)
            val matches = ArrayList<Match>(limit.coerceIn(1, MAX_MATCHES))
            val max = limit.coerceIn(1, MAX_MATCHES)
            val templateWidth = template.cols()
            val templateHeight = template.rows()
            val resultWidth = result.cols()
            val resultHeight = result.rows()
            while (matches.size < max) {
                val mm = Core.minMaxLoc(result)
                if (mm.maxVal < threshold) break
                matches.add(
                    Match(
                        x = region.left + mm.maxLoc.x.toInt() + templateWidth / 2,
                        y = region.top + mm.maxLoc.y.toInt() + templateHeight / 2,
                        confidence = mm.maxVal,
                    ),
                )
                // 抑制已命中位置附近的响应，避免同一目标被重复计数。
                val left = (mm.maxLoc.x - templateWidth / 2).toInt().coerceIn(0, resultWidth - 1)
                val top = (mm.maxLoc.y - templateHeight / 2).toInt().coerceIn(0, resultHeight - 1)
                val right = (mm.maxLoc.x + templateWidth / 2).toInt().coerceIn(0, resultWidth - 1)
                val bottom = (mm.maxLoc.y + templateHeight / 2).toInt().coerceIn(0, resultHeight - 1)
                Imgproc.rectangle(
                    result,
                    Point(left.toDouble(), top.toDouble()),
                    Point(right.toDouble(), bottom.toDouble()),
                    Scalar(0.0),
                    -1,
                )
            }
            matches
        } catch (t: Throwable) {
            logBus.error("图像匹配失败: ${t.message}")
            emptyList()
        } finally {
            screenMat?.release()
            result?.release()
            if (cropped !== screen) cropped.recycle()
        }
    }

    /** 复用的整屏像素缓冲，避免每次找色都分配数 MB 的 IntArray。 */
    private var colorPixels = IntArray(0)

    private fun findColorMatches(
        bitmap: Bitmap,
        target: Int,
        tolerance: Int,
        region: Rect,
        limit: Int,
    ): List<ColorMatch> {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return emptyList()
        val count = width * height
        if (colorPixels.size < count) colorPixels = IntArray(count)
        val pixels = colorPixels
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val tr = (target shr 16) and 0xFF
        val tg = (target shr 8) and 0xFF
        val tb = target and 0xFF
        val tol = tolerance.coerceIn(0, 255)
        val max = limit.coerceIn(1, MAX_MATCHES)
        val matches = ArrayList<ColorMatch>(max)
        for (y in region.top until region.bottom) {
            val row = y * width
            for (x in region.left until region.right) {
                val p = pixels[row + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (abs(r - tr) <= tol && abs(g - tg) <= tol && abs(b - tb) <= tol) {
                    matches.add(ColorMatch(x, y))
                    if (matches.size >= max) return matches
                }
            }
        }
        return matches
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

    private fun screenshot(): Bitmap? = screenshotProvider()

    /**
     * 解析区域参数；空字符串表示整屏。返回 null 表示参数无效（已记录日志）。
     */
    private fun parseRegion(json: String, width: Int, height: Int, label: String): Rect? {
        if (json.isBlank()) return Rect(0, 0, width, height)
        return try {
            val obj = JSONObject(json)
            val x = obj.optInt("x", 0)
            val y = obj.optInt("y", 0)
            val w = obj.optInt("width", obj.optInt("w", width))
            val h = obj.optInt("height", obj.optInt("h", height))
            val rect = Rect(x, y, x + w, y + h)
            if (w <= 0 || h <= 0 || !rect.intersect(0, 0, width, height)) {
                logBus.warn("$label 区域无效: $json")
                null
            } else {
                rect
            }
        } catch (t: Throwable) {
            logBus.warn("$label 区域解析失败: ${t.message}")
            null
        }
    }

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

        const val MAX_MATCHES = 200
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
