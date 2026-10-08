package com.benton.izukijs.runtime.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.JavascriptInterface
import com.benton.izukijs.ocr.OcrProcessor
import com.benton.izukijs.runtime.LogBus
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
        val bitmap = BitmapFactory.decodeFile(path) ?: run {
            logBus.warn("OCR 识别失败：无法读取图片 $path")
            return null
        }
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
        val result = try {
            ocrProcessor.recognize(bitmap)
        } finally {
            bitmap.recycle()
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
                    block.confidence?.let { put("conf", it) }
                },
            )
        }
        val json = JSONObject().apply {
            put("text", result.text)
            put("blocks", blocks)
        }
        logBus.debug("OCR 结构化识别 → ${result.blocks.size} 块（${bitmap.width}×${bitmap.height}）")
        return json.toString()
    }

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

        val block = result.blocks.firstOrNull { it.text.contains(query) }
        return if (block == null) {
            logBus.debug("OCR 查找 \"$query\" → 未找到（共 ${result.blocks.size} 块）✗")
            null
        } else {
            logBus.debug("OCR 查找 \"$query\" → (${block.x}, ${block.y}) ✓")
            "${block.x},${block.y},${block.width},${block.height}"
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
