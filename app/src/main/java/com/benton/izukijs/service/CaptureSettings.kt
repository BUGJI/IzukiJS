package com.benton.izukijs.service

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 截图压缩设置。缩放仅作用于「保存到文件」与「在线 OCR 上传」，
 * 不会改变图像匹配 / 找色 / 本地 OCR 使用的全分辨率坐标，故不会破坏点击坐标。
 */
data class CaptureSettings(
    /** 缩放百分比，100 表示原始分辨率。 */
    val scalePercent: Int = 100,
    /** JPEG 质量，50–100。 */
    val jpegQuality: Int = 90,
)

/** SharedPreferences 持久化 + 内存缓存。 */
class CaptureSettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<CaptureSettings> = _settings.asStateFlow()

    fun current(): CaptureSettings = _settings.value

    fun save(value: CaptureSettings) {
        _settings.value = value
        prefs.edit()
            .putInt(KEY_SCALE, value.scalePercent)
            .putInt(KEY_QUALITY, value.jpegQuality)
            .apply()
    }

    private fun load(): CaptureSettings = CaptureSettings(
        scalePercent = prefs.getInt(KEY_SCALE, 100),
        jpegQuality = prefs.getInt(KEY_QUALITY, 90),
    )

    private companion object {
        const val PREFS = "izukijs_capture"
        const val KEY_SCALE = "scale_percent"
        const val KEY_QUALITY = "jpeg_quality"
    }
}

/**
 * 按百分比等比缩放位图。100（或更大）时直接返回原图，不做复制，
 * 调用方可用 `result !== this` 判断是否需要 recycle 结果。
 */
fun Bitmap.scaledByPercent(scalePercent: Int): Bitmap {
    val ratio = scalePercent.coerceIn(10, 100) / 100f
    if (ratio >= 1f) return this
    val targetWidth = (width * ratio).toInt().coerceAtLeast(1)
    val targetHeight = (height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
}
