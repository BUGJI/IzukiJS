package com.benton.izukijs.runtime.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.JavascriptInterface
import com.benton.izukijs.ocr.OcrProcessor

/**
 * OCR 识别 API。挂在全局 `ocr` 命名空间，识别引擎（本地/在线）由设备配置决定。
 */
class OcrApi(
    private val ocrProcessor: OcrProcessor,
    private val screenshotProvider: () -> Bitmap?,
) {

    @JavascriptInterface
    fun recognize(): String? {
        val bitmap = screenshot() ?: return null
        return try {
            ocrProcessor.recognize(bitmap)?.text
        } finally {
            bitmap.recycle()
        }
    }

    @JavascriptInterface
    fun recognizePath(path: String): String? {
        val bitmap = BitmapFactory.decodeFile(path) ?: return null
        return try {
            ocrProcessor.recognize(bitmap)?.text
        } finally {
            bitmap.recycle()
        }
    }

    /** 查找包含指定文字的元素，返回 "x,y,width,height"（包围盒中心）。 */
    @JavascriptInterface
    fun findRaw(query: String): String? {
        val bitmap = screenshot() ?: return null
        val result = try {
            ocrProcessor.recognize(bitmap)
        } finally {
            bitmap.recycle()
        } ?: return null

        result.blocks.firstOrNull { it.text.contains(query) }?.let {
            return "${it.x},${it.y},${it.width},${it.height}"
        }
        return null
    }

    private fun screenshot(): Bitmap? = screenshotProvider()
}
