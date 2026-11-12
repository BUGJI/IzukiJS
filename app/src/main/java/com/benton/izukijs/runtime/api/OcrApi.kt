package com.benton.izukijs.runtime.api

import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import com.benton.izukijs.ocr.OcrLineSynthesis
import com.benton.izukijs.ocr.OcrMatcher
import com.benton.izukijs.ocr.OcrProcessor
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.vision.Box
import com.benton.izukijs.vision.RectSpec
import com.benton.izukijs.vision.RoiResolver
import com.benton.izukijs.vision.RoiSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * OCR 识别 API。挂在全局 `ocr` 命名空间，识别引擎（本地/在线）由设备配置决定。
 *
 * 每次识别 / 查找都会以 DEBUG 级别写入 [logBus]，记录尺寸、耗时、识别到的文字，
 * 便于在「日志」页回溯脚本实际的视觉识别过程。
 */
class OcrApi(
    private val ocrProcessor: OcrProcessor,
    private val screenshotProvider: () -> Bitmap?,
    private val logBus: LogBus,
) {

    /** 预热本地 OCR 客户端；不执行识别，供脚本启动时降低首次调用延迟。 */
    fun prewarm() = ocrProcessor.prewarm()

    @JavascriptInterface
    fun recognize(): String? {
        val bitmap = screenshot() ?: run {
            logBus.warn("OCR 识别失败：无法获取屏幕截图")
            return null
        }
        return recognizeBitmap("屏幕", bitmap)
    }

    @JavascriptInterface
    fun recognizePath(path: String): String? {
        val bitmap = ImageDecode.decode(path, logBus, "OCR 识别", allowDownscale = true)
            ?: return null
        return recognizeBitmap(path, bitmap)
    }

    /**
     * 识别当前屏幕，返回结构化结果（JSON）：
     * `{"text":"全文","blocks":[{"text","x","y","w","h","conf"}]}`。
     * 坐标均为全分辨率屏幕像素（x/y 为包围盒中心），可直接用于点击。
     */
    @JavascriptInterface
    fun recognizeBlocksJson(): String? {
        val bitmap = screenshot() ?: run {
            logBus.warn("OCR 识别失败：无法获取屏幕截图")
            return null
        }
        return recognizeBlocksJson(bitmap)
    }

    /** 识别指定图像（调用方转移所有权，本方法负责 recycle）。供 MCP 按 target 识别虚拟屏。 */
    fun recognizeBlocksJson(source: Bitmap): String? {
        val width = source.width
        val height = source.height
        val result = try {
            ocrProcessor.recognize(source)
        } finally {
            source.recycle()
        } ?: return null
        val blocks = JSONArray()
        result.blocks.forEach { block ->
            blocks.put(
                JSONObject().apply {
                    put("text", block.text)
                    put("x", block.x)
                    put("y", block.y)
                    put("w", block.width)
                    put("h", block.height)
                    put("left", block.left)
                    put("top", block.top)
                    put("right", block.right)
                    put("bottom", block.bottom)
                    if (block.suspicious) put("suspicious", true)
                    block.confidence?.let { put("conf", it) }
                },
            )
        }
        val json = JSONObject().apply {
            put("text", result.text)
            put("image", JSONObject().put("w", width).put("h", height))
            put("blocks", blocks)
        }
        val suspicious = result.blocks.count { it.suspicious }
        logBus.debug(
            "OCR 结构化识别 → ${result.blocks.size} 块（${width}×$height）" +
                if (suspicious > 0) "，$suspicious 块坐标已裁剪" else "",
        )
        return json.toString()
    }

    /** 带选项的查找：支持 roi、threshold、fuzzy。 */
    @JavascriptInterface
    fun findWithOptionsRaw(query: String, optionsJson: String): String? {
        val bitmap = screenshot() ?: run {
            logBus.warn("OCR 查找 \"$query\" 失败：无法获取屏幕截图")
            return null
        }
        return try {
            val options = if (optionsJson.isBlank()) JSONObject() else JSONObject(optionsJson)
            val rois = resolveRois(options, bitmap.width, bitmap.height) ?: return null
            val threshold = options.optDouble("threshold", 0.0)
            val fuzzy = options.optBoolean("fuzzy", true)
            for (roi in rois) {
                val crop = if (isFull(roi, bitmap)) {
                    bitmap
                } else {
                    Bitmap.createBitmap(bitmap, roi.left, roi.top, roi.width, roi.height)
                }
                try {
                    val result = ocrProcessor.recognize(crop) ?: continue
                    val lines = OcrLineSynthesis.synthesize(result.blocks)
                        .filter { it.confidence == null || it.confidence >= threshold }
                    val match = OcrMatcher.match(lines, query, fuzzy).firstOrNull() ?: continue
                    val line = match.line
                    logBus.debug("OCR 查找 \"$query\" → (${roi.left + line.x}, ${roi.top + line.y}) ✓")
                    return "${roi.left + line.x},${roi.top + line.y},${line.width},${line.height}"
                } finally {
                    if (crop !== bitmap) crop.recycle()
                }
            }
            logBus.debug("OCR 查找 \"$query\" → 未找到 ✗")
            null
        } catch (t: Throwable) {
            logBus.warn("OCR 查找失败: ${t.message}")
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** 带 ROI 的结构化识别。 */
    @JavascriptInterface
    fun recognizeBlocksJsonWithOptions(optionsJson: String): String? {
        val bitmap = screenshot() ?: run {
            logBus.warn("OCR 识别失败：无法获取屏幕截图")
            return null
        }
        return try {
            val options = if (optionsJson.isBlank()) JSONObject() else JSONObject(optionsJson)
            val roi = resolveRois(options, bitmap.width, bitmap.height)?.firstOrNull() ?: return null
            val crop = if (isFull(roi, bitmap)) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, roi.left, roi.top, roi.width, roi.height)
            }
            try {
                val result = ocrProcessor.recognize(crop) ?: return null
                val blocks = JSONArray()
                result.blocks.forEach { block ->
                    blocks.put(
                        JSONObject().apply {
                            put("text", block.text)
                            put("x", roi.left + block.x)
                            put("y", roi.top + block.y)
                            put("w", block.width)
                            put("h", block.height)
                            put("left", roi.left + block.left)
                            put("top", roi.top + block.top)
                            put("right", roi.left + block.right)
                            put("bottom", roi.top + block.bottom)
                            block.confidence?.let { put("conf", it) }
                        },
                    )
                }
                JSONObject().apply {
                    put("text", result.text)
                    put("image", JSONObject().put("w", bitmap.width).put("h", bitmap.height))
                    put("blocks", blocks)
                }.toString()
            } finally {
                if (crop !== bitmap) crop.recycle()
            }
        } catch (t: Throwable) {
            logBus.warn("OCR 结构化识别失败: ${t.message}")
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun resolveRois(options: JSONObject, width: Int, height: Int): List<Box>? {
        val roi = options.optJSONObject("roi") ?: return listOf(Box(0, 0, width, height))
        val spec = RoiSpec(
            rect = RectSpec(
                x = roi.optInt("x", 0),
                y = roi.optInt("y", 0),
                width = roi.optInt("width", roi.optInt("w", 0)),
                height = roi.optInt("height", roi.optInt("h", 0)),
            ),
        )
        val boxes = RoiResolver.resolve(spec, emptyMap(), width, height)
        return if (boxes.isEmpty()) null else boxes
    }

    private fun isFull(roi: Box, bitmap: Bitmap): Boolean =
        roi.left == 0 && roi.top == 0 && roi.width == bitmap.width && roi.height == bitmap.height

    /** 查找包含指定文字的元素，返回 "x,y,width,height"（包围盒中心）。 */
    @JavascriptInterface
    fun findRaw(query: String): String? {
        val bitmap = screenshot() ?: run {
            logBus.warn("OCR 查找 \"$query\" 失败：无法获取屏幕截图")
            return null
        }
        val result = try {
            ocrProcessor.recognize(bitmap)
        } finally {
            bitmap.recycle()
        } ?: run {
            logBus.debug("OCR 查找 \"$query\" → 无识别结果 ✗")
            return null
        }

        // 先按行聚合再匹配：OCR 常把一句话切成多块，单块包含匹配会漏掉整句。
        val lines = com.benton.izukijs.ocr.OcrLineSynthesis.synthesize(result.blocks)
        val match = com.benton.izukijs.ocr.OcrMatcher.match(lines, query).firstOrNull()
        return if (match == null) {
            logBus.debug("OCR 查找 \"$query\" → 未找到（共 ${result.blocks.size} 块）✗")
            null
        } else {
            val line = match.line
            logBus.debug("OCR 查找 \"$query\" → (${line.x}, ${line.y}) ✓")
            "${line.x},${line.y},${line.width},${line.height}"
        }
    }

    private fun recognizeBitmap(label: String, bitmap: Bitmap): String? {
        val started = System.currentTimeMillis()
        return try {
            val result = ocrProcessor.recognize(bitmap)
            val text = result?.text
            val elapsed = System.currentTimeMillis() - started
            val head = "${bitmap.width}×${bitmap.height}"
            val summary = "${result?.blocks?.size ?: 0} 块 / ${text?.length ?: 0} 字 (${elapsed}ms)"
            logBus.debug(
                if (text.isNullOrBlank()) {
                    "OCR 识别 $label $head → $summary：无文字"
                } else {
                    "OCR 识别 $label $head → $summary：${text.preview()}"
                },
            )
            text
        } finally {
            bitmap.recycle()
        }
    }

    private fun screenshot(): Bitmap? = screenshotProvider()

    private fun String.preview(max: Int = 120): String =
        if (length <= max) this else take(max) + "…"
}
