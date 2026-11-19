package com.benton.izukijs.runtime.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.webkit.JavascriptInterface
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.perf.PerfMetrics
import com.benton.izukijs.ocr.OcrProcessor
import com.benton.izukijs.vision.VisionEngine
import com.benton.izukijs.vision.VisionResult
import com.benton.izukijs.vision.VisionSpec
import com.benton.izukijs.vision.VisionSpecException
import com.benton.izukijs.vision.VisionSpecParser
import com.benton.izukijs.vision.selectIndex
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * 图像能力 API（OpenCV）。挂在全局 `images` 命名空间。
 *
 * 本类负责「统一识别 + 模板保存 + 对外门面」；找图 / 找色的具体扫描分别委托给
 * [ImageMatchOps] / [ImageColorOps]，OpenCV 加载由 [ImageOpenCv] 统一管理。
 * 原生方法返回 "x,y[,confidence]" 或 JSON 数组，便于通过 JS 预置脚本包装成对象。
 */
class ImageApi(
    private val context: Context,
    private val screenshotProvider: () -> Bitmap?,
    private val logBus: LogBus,
    ocrProcessor: OcrProcessor? = null,
) : Closeable {

    private val visionEngineDelegate = lazy { VisionEngine(logBus, ocrProcessor) }
    private val visionEngine by visionEngineDelegate
    private val matchOps by lazy { ImageMatchOps(screenshotProvider, logBus) }
    private val colorOps by lazy { ImageColorOps(screenshotProvider, logBus) }

    /** 像素读取的最近一张图缓存；按路径与 mtime 校验，避免循环取点反复解码。 */
    private val pixelCacheLock = Any()
    private var pixelCachePath: String? = null
    private var pixelCacheModified: Long = 0L
    private var pixelCacheBitmap: Bitmap? = null

    /** 供 `pipeline` 复用同一套模板 / 模型缓存；生命周期由本对象负责。 */
    internal fun visionEngine(): VisionEngine = visionEngine

    override fun close() {
        releasePixelCache()
        if (visionEngineDelegate.isInitialized()) visionEngine.close()
    }

    // ---- 统一识别（参数对齐 MaaFramework Pipeline） ----

    @JavascriptInterface
    fun recognizeRaw(specJson: String): String? {
        if (!ImageOpenCv.ensure(logBus)) return null
        val screen = screenshot() ?: run {
            logBus.warn("识别失败：无法获取屏幕截图")
            return null
        }
        return try {
            recognizeOne(screen, specJson, "")
        } finally {
            screen.recycle()
        }
    }

    @JavascriptInterface
    fun recognizeAllRaw(specJson: String): String {
        if (!ImageOpenCv.ensure(logBus)) return "[]"
        val screen = screenshot() ?: run {
            logBus.warn("识别失败：无法获取屏幕截图")
            return "[]"
        }
        return try {
            recognizeAll(screen, specJson, "")
        } finally {
            screen.recycle()
        }
    }

    @JavascriptInterface
    fun recognizeInRaw(screenPath: String, specJson: String): String? {
        if (!ImageOpenCv.ensure(logBus)) return null
        val screen = ImageDecode.decode(screenPath, logBus, "识别") ?: return null
        return try {
            recognizeOne(screen, specJson, screenPathLabel(screenPath))
        } finally {
            screen.recycle()
        }
    }

    @JavascriptInterface
    fun recognizeAllInRaw(screenPath: String, specJson: String): String {
        if (!ImageOpenCv.ensure(logBus)) return "[]"
        val screen = ImageDecode.decode(screenPath, logBus, "识别") ?: return "[]"
        return try {
            recognizeAll(screen, specJson, screenPathLabel(screenPath))
        } finally {
            screen.recycle()
        }
    }

    private fun recognizeOne(screen: Bitmap, specJson: String, suffix: String): String? = try {
        val spec = VisionSpecParser.parse(specJson)
        val results = PerfMetrics.measure("vision.recognize") { visionEngine.recognize(spec, screen) }
        val selected = selectIndex(results, spec.index)
        if (selected == null) {
            logBus.debugThrottled("vision.recognize", "识别 ${typeName(spec)}$suffix → 未命中 ✗")
            null
        } else {
            logBus.debugThrottled(
                "vision.recognize",
                "识别 ${typeName(spec)}$suffix → (${selected.box.centerX}, ${selected.box.centerY}) " +
                    "score=${selected.score.confidenceText()} ✓",
            )
            resultToJson(selected).toString()
        }
    } catch (t: VisionSpecException) {
        logBus.warn("识别参数错误：${t.message}")
        null
    } catch (t: Throwable) {
        logBus.error("识别失败: ${t.message}")
        null
    }

    private fun recognizeAll(screen: Bitmap, specJson: String, suffix: String): String = try {
        val spec = VisionSpecParser.parse(specJson)
        val results = PerfMetrics.measure("vision.recognize") { visionEngine.recognize(spec, screen) }
        val array = JSONArray()
        results.forEach { array.put(resultToJson(it)) }
        logBus.debugThrottled("vision.recognize", "识别 ${typeName(spec)}$suffix → ${results.size} 个结果")
        array.toString()
    } catch (t: VisionSpecException) {
        logBus.warn("识别参数错误：${t.message}")
        "[]"
    } catch (t: Throwable) {
        logBus.error("识别失败: ${t.message}")
        "[]"
    }

    private fun screenPathLabel(path: String): String = " @ ${File(path).name}"

    @JavascriptInterface
    fun compareRaw(lhsPath: String, rhsPath: String, optionsJson: String): String? {
        if (!ImageOpenCv.ensure(logBus)) return null
        val lhs = ImageDecode.decode(lhsPath, logBus, "compare", allowDownscale = true)
            ?: return null
        val rhs = ImageDecode.decode(rhsPath, logBus, "compare", allowDownscale = true)
            ?: run {
                lhs.recycle()
                return null
            }
        return try {
            val options = if (optionsJson.isBlank()) JSONObject() else JSONObject(optionsJson)
            val (score, matched) = visionEngine.compare(lhs, rhs, options)
            JSONObject().apply {
                put("score", score)
                put("matched", matched)
            }.toString()
        } catch (t: Throwable) {
            logBus.warn("compare 失败: ${t.message}")
            null
        } finally {
            rhs.recycle()
            lhs.recycle()
        }
    }

    private fun resultToJson(result: VisionResult): JSONObject = JSONObject().apply {
        put("x", result.box.centerX)
        put("y", result.box.centerY)
        put("left", result.box.left)
        put("top", result.box.top)
        put("right", result.box.right)
        put("bottom", result.box.bottom)
        put("w", result.box.width)
        put("h", result.box.height)
        put("score", result.score)
        put("count", result.count)
        result.text?.let { put("text", it) }
        if (result.clsIndex >= 0) put("clsIndex", result.clsIndex)
        result.label?.let { put("label", it) }
    }

    private fun typeName(spec: VisionSpec): String = spec::class.simpleName ?: "Vision"

    // ---- 找图 ----

    @JavascriptInterface
    fun findImageRaw(templatePath: String, threshold: Double): String? =
        matchOps.findImageRaw(templatePath, threshold)

    @JavascriptInterface
    fun findImageRegionRaw(
        templatePath: String,
        threshold: Double,
        regionJson: String,
    ): String? = matchOps.findImageRegionRaw(templatePath, threshold, regionJson)

    @JavascriptInterface
    fun findAllImagesRaw(
        templatePath: String,
        threshold: Double,
        regionJson: String,
        limit: Int,
    ): String = matchOps.findAllImagesRaw(templatePath, threshold, regionJson, limit)

    @JavascriptInterface
    fun findImageInRaw(screenPath: String, templatePath: String, threshold: Double): String? =
        matchOps.findImageInRaw(screenPath, templatePath, threshold)

    // ---- 找色 ----

    @JavascriptInterface
    fun findColorRaw(color: String, threshold: Double): String? =
        colorOps.findColorRaw(color, threshold)

    @JavascriptInterface
    fun findColorRegionRaw(
        color: String,
        threshold: Double,
        regionJson: String,
    ): String? = colorOps.findColorRegionRaw(color, threshold, regionJson)

    @JavascriptInterface
    fun findAllColorsRaw(
        color: String,
        threshold: Double,
        regionJson: String,
        limit: Int,
    ): String = colorOps.findAllColorsRaw(color, threshold, regionJson, limit)

    @JavascriptInterface
    fun findColorRegionsRaw(
        color: String,
        threshold: Double,
        optionsJson: String,
    ): String = colorOps.findColorRegionsRaw(color, threshold, optionsJson)

    @JavascriptInterface
    fun findColorRegionsInRaw(
        screenPath: String,
        color: String,
        threshold: Double,
        optionsJson: String,
    ): String = colorOps.findColorRegionsInRaw(screenPath, color, threshold, optionsJson)

    @JavascriptInterface
    fun findColorInRaw(
        screenPath: String,
        color: String,
        threshold: Double,
    ): String? = colorOps.findColorInRaw(screenPath, color, threshold)

    // ---- 模板 ----

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

    // ---- 文件图片对象（Auto.js Image 形状） ----

    /** 只读尺寸，不分配整张 Bitmap。 */
    @JavascriptInterface
    fun sizeRaw(path: String): String? {
        if (path.isBlank()) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(path, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return JSONObject()
            .put("width", options.outWidth)
            .put("height", options.outHeight)
            .toString()
    }

    @JavascriptInterface
    fun pixelRaw(path: String, x: Int, y: Int): String? {
        val entry = pixelBitmap(path) ?: return null
        return try {
            val bitmap = entry.bitmap
            if (x < 0 || y < 0 || x >= bitmap.width || y >= bitmap.height) {
                null
            } else {
                String.format("#%08X", bitmap.getPixel(x, y))
            }
        } finally {
            if (!entry.cached) entry.bitmap.recycle()
        }
    }

    private class PixelBitmap(val bitmap: Bitmap, val cached: Boolean)

    private fun pixelBitmap(path: String): PixelBitmap? {
        if (path.isBlank()) return null
        val modified = File(path).lastModified()
        synchronized(pixelCacheLock) {
            val cached = pixelCacheBitmap
            if (cached != null && !cached.isRecycled &&
                pixelCachePath == path && pixelCacheModified == modified
            ) {
                return PixelBitmap(cached, cached = true)
            }
        }
        // 解码不持锁：一次取点不应阻塞同一运行时的其它图片操作。
        val decoded = ImageDecode.decode(path, logBus, "像素") ?: return null
        // 只缓存常见屏幕尺寸，避免一张大图长期占用几十 MB。
        if (decoded.width.toLong() * decoded.height > PIXEL_CACHE_MAX_PIXELS) {
            return PixelBitmap(decoded, cached = false)
        }
        synchronized(pixelCacheLock) {
            val existing = pixelCacheBitmap
            if (existing !== decoded && existing != null && !existing.isRecycled) {
                existing.recycle()
            }
            pixelCachePath = path
            pixelCacheModified = modified
            pixelCacheBitmap = decoded
        }
        return PixelBitmap(decoded, cached = true)
    }

    private fun releasePixelCache() {
        synchronized(pixelCacheLock) {
            pixelCacheBitmap?.let { if (!it.isRecycled) it.recycle() }
            pixelCacheBitmap = null
            pixelCachePath = null
            pixelCacheModified = 0L
        }
    }

    @JavascriptInterface
    fun saveRaw(sourcePath: String, targetPath: String, quality: Int): Boolean {
        val bitmap = ImageDecode.decode(sourcePath, logBus, "保存图片") ?: return false
        return try {
            writeBitmap(bitmap, targetPath, quality)
        } finally {
            bitmap.recycle()
        }
    }

    @JavascriptInterface
    fun clipRaw(
        sourcePath: String,
        targetPath: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Boolean {
        val bitmap = ImageDecode.decode(sourcePath, logBus, "裁剪图片") ?: return false
        return try {
            val rect = Rect(x, y, x + width, y + height)
            if (width <= 0 || height <= 0 || !rect.intersect(0, 0, bitmap.width, bitmap.height)) {
                logBus.warn("裁剪图片失败：区域无效 ($x,$y ${width}x$height)")
                false
            } else {
                val crop = Bitmap.createBitmap(
                    bitmap,
                    rect.left,
                    rect.top,
                    rect.width(),
                    rect.height(),
                )
                try {
                    writeBitmap(crop, targetPath, 100)
                } finally {
                    if (crop !== bitmap) crop.recycle()
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    @JavascriptInterface
    fun clipToCacheRaw(
        sourcePath: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): String? {
        val target = createCacheImage("image_clip_") ?: return null
        return if (clipRaw(sourcePath, target.absolutePath, x, y, width, height)) {
            target.absolutePath
        } else {
            target.delete()
            null
        }
    }

    @JavascriptInterface
    fun resizeToCacheRaw(sourcePath: String, width: Int, height: Int): String? {
        val target = createCacheImage("image_resize_") ?: return null
        return if (resizeRaw(sourcePath, target.absolutePath, width, height)) {
            target.absolutePath
        } else {
            target.delete()
            null
        }
    }

    /** 同一毫秒内多次裁剪 / 缩放不能互相覆盖，用 createTempFile 保证唯一。 */
    private fun createCacheImage(prefix: String): File? =
        runCatching { File.createTempFile(prefix, ".png", context.cacheDir) }.getOrNull()

    @JavascriptInterface
    fun resizeRaw(sourcePath: String, targetPath: String, width: Int, height: Int): Boolean {
        val bitmap = ImageDecode.decode(sourcePath, logBus, "缩放图片") ?: return false
        return try {
            if (width <= 0 || height <= 0) {
                logBus.warn("缩放图片失败：尺寸无效 ${width}x$height")
                false
            } else {
                val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                try {
                    writeBitmap(scaled, targetPath, 100)
                } finally {
                    if (scaled !== bitmap) scaled.recycle()
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeBitmap(bitmap: Bitmap, path: String, quality: Int): Boolean = runCatching {
        val file = File(path)
        file.parentFile?.mkdirs()
        val format = if (file.name.endsWith(".jpg", true) || file.name.endsWith(".jpeg", true)) {
            Bitmap.CompressFormat.JPEG
        } else {
            Bitmap.CompressFormat.PNG
        }
        FileOutputStream(file).use { out ->
            bitmap.compress(format, quality.coerceIn(1, 100), out)
        }
        true
    }.getOrElse {
        logBus.warn("保存图片失败: ${it.message}")
        false
    }

    @JavascriptInterface
    fun available(): Boolean = ImageOpenCv.ensure(logBus)

    private fun screenshot(): Bitmap? = screenshotProvider()

    companion object {
        /** 像素缓存上限：约 4MP，常见 1080p 屏幕可命中，超大图直接读后释放。 */
        private const val PIXEL_CACHE_MAX_PIXELS = 4_000_000L

        /** 后台预热 OpenCV，避免脚本第一次找图时同步加载 native 库。 */
        fun prewarm() = ImageOpenCv.prewarm()
    }
}
