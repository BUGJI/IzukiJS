package com.benton.izukijs.service

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import com.benton.izukijs.core.prefs.PrefsStateStore
import kotlinx.coroutines.flow.StateFlow

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
class CaptureSettingsRepository(context: Context) : PrefsStateStore<CaptureSettings>(
    context = context,
    prefsName = PREFS,
    defaultValue = CaptureSettings(),
) {

    val settings: StateFlow<CaptureSettings> get() = state

    override fun read(): CaptureSettings = CaptureSettings(
        scalePercent = prefs.getInt(KEY_SCALE, 100),
        jpegQuality = prefs.getInt(KEY_QUALITY, 90),
    )

    override fun write(editor: SharedPreferences.Editor, value: CaptureSettings) {
        editor
            .putInt(KEY_SCALE, value.scalePercent)
            .putInt(KEY_QUALITY, value.jpegQuality)
    }

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
