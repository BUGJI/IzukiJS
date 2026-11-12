package com.benton.izukijs.runtime.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.benton.izukijs.core.log.LogBus

/**
 * 外部图片解码入口：先读取尺寸，再决定是否允许采样。
 *
 * 返回坐标的接口（找图 / 找色 / 统一识别）不能采样，否则坐标和模板尺寸都会失真；
 * 这类接口在图片过大时直接拒绝解码以避免 OOM。仅返回文本 / 分数的接口可安全采样。
 */
internal object ImageDecode {

    private const val MAX_DIMENSION = 8192
    private const val MAX_PIXELS = 16_000_000L

    fun decode(
        path: String,
        logBus: LogBus,
        label: String,
        allowDownscale: Boolean = false,
    ): Bitmap? {
        if (path.isBlank()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(path, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            logBus.warn("$label 无法读取图片 $path")
            return null
        }
        if (!allowDownscale) {
            if (width > MAX_DIMENSION || height > MAX_DIMENSION ||
                width.toLong() * height > MAX_PIXELS
            ) {
                logBus.warn("$label 图片过大（${width}×$height），已拒绝解码以避免 OOM")
                return null
            }
        }
        val sample = if (allowDownscale) {
            var value = 1
            while (width / value > MAX_DIMENSION || height / value > MAX_DIMENSION ||
                (width.toLong() / value) * (height / value) > MAX_PIXELS
            ) {
                value *= 2
            }
            value
        } else {
            1
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull() ?: run {
            logBus.warn("$label 图片解码失败 $path")
            null
        }
    }
}
