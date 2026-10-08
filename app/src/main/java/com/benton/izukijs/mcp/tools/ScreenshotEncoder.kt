package com.benton.izukijs.mcp.tools

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import com.benton.izukijs.service.scaledByPercent
import java.io.ByteArrayOutputStream

/** 截图编码格式。webp 仅在 API 30+ 使用有损压缩，低版本自动回退 JPEG。 */
enum class ScreenshotFormat(val id: String, val mimeType: String) {
    JPEG("jpeg", "image/jpeg"),
    WEBP("webp", "image/webp"),
    ;

    companion object {
        fun from(id: String?): ScreenshotFormat =
            if (id?.trim()?.lowercase() == WEBP.id) WEBP else JPEG
    }
}

/** 编码后的屏幕图像，用于 MCP `image` 内容块。 */
data class EncodedImage(
    val base64: String,
    val mimeType: String,
    /** 编码后图像的像素尺寸。 */
    val width: Int,
    val height: Int,
    /** 原始屏幕的像素尺寸（点击 / 控件树 / OCR 使用的坐标系）。 */
    val screenWidth: Int,
    val screenHeight: Int,
    /** 裁剪区域 `[left, top, right, bottom]`（屏幕像素），未裁剪为 null。 */
    val region: List<Int>? = null,
)

/**
 * 把屏幕位图裁剪、缩放并编码，用于 MCP `image` 内容块。
 *
 * MCP 截图不再复用「保存截图」的压缩设置——那个设置改变的是图像尺寸而非坐标，
 * 会让模型拿到缩略图却用真实像素点击。这里显式接收区域 / 缩放 / 格式，并回传原始屏幕
 * 尺寸，模型即可用 `image_size` 与 `screen_size` 换算，或直接使用归一化坐标。
 */
object ScreenshotEncoder {

    fun encode(
        source: Bitmap,
        scalePercent: Int = 100,
        jpegQuality: Int = 90,
        region: Rect? = null,
        format: ScreenshotFormat = ScreenshotFormat.JPEG,
    ): EncodedImage {
        val cropped = crop(source, region)
        val scaled = cropped.scaledByPercent(scalePercent)
        return try {
            val out = ByteArrayOutputStream()
            val losslessWebp = format == ScreenshotFormat.WEBP && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            val compressFormat = when {
                losslessWebp -> Bitmap.CompressFormat.WEBP_LOSSY
                else -> Bitmap.CompressFormat.JPEG
            }
            scaled.compress(compressFormat, jpegQuality.coerceIn(1, 100), out)
            EncodedImage(
                base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
                mimeType = if (losslessWebp) format.mimeType else ScreenshotFormat.JPEG.mimeType,
                width = scaled.width,
                height = scaled.height,
                screenWidth = source.width,
                screenHeight = source.height,
                region = region?.let { listOf(it.left, it.top, it.right, it.bottom) },
            )
        } finally {
            if (scaled !== cropped) scaled.recycle()
            if (cropped !== source) cropped.recycle()
        }
    }

    /** 按 [region] 裁剪，越界自动 clamp；区域无效应视为整屏。 */
    private fun crop(source: Bitmap, region: Rect?): Bitmap {
        if (region == null) return source
        val left = region.left.coerceIn(0, source.width)
        val top = region.top.coerceIn(0, source.height)
        val right = region.right.coerceIn(left, source.width)
        val bottom = region.bottom.coerceIn(top, source.height)
        if (right - left <= 0 || bottom - top <= 0) return source
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }
}
