package com.benton.izukijs.runtime.api

import android.content.Context
import android.graphics.Bitmap
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

    /** 供 `pipeline` 复用同一套模板 / 模型缓存；生命周期由本对象负责。 */
    internal fun visionEngine(): VisionEngine = visionEngine

    override fun close() {
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

    @JavascriptInterface
    fun available(): Boolean = ImageOpenCv.ensure(logBus)

    private fun screenshot(): Bitmap? = screenshotProvider()

    companion object {
        /** 后台预热 OpenCV，避免脚本第一次找图时同步加载 native 库。 */
        fun prewarm() = ImageOpenCv.prewarm()
    }
}
