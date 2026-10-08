package com.benton.izukijs.mcp.tools

import android.graphics.Bitmap
import android.util.Base64
import com.benton.izukijs.service.CaptureSettings
import com.benton.izukijs.service.scaledByPercent
import java.io.ByteArrayOutputStream

/** 编码后的屏幕图像，用于 MCP `image` 内容块。 */
data class EncodedImage(
    val base64: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
)

/**
 * 把屏幕位图按 [CaptureSettings] 缩放并编码为 JPEG（Base64），
 * 与「保存截图」保持同一套压缩策略，控制 MCP 传输体积。
 */
object ScreenshotEncoder {

    fun encode(source: Bitmap, settings: CaptureSettings): EncodedImage {
        val scaled = source.scaledByPercent(settings.scalePercent)
        return try {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, settings.jpegQuality, out)
            EncodedImage(
                base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
                mimeType = "image/jpeg",
                width = scaled.width,
                height = scaled.height,
            )
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }
}
