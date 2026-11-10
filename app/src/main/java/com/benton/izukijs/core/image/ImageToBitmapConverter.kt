package com.benton.izukijs.core.image

import android.graphics.Bitmap
import android.media.Image

/**
 * 把 [ImageReader] 产出的 RGBA_8888 [Image] 转成 [Bitmap]。
 *
 * 处理 `rowStride` 行填充：标准情况下逐行拷进复用的 [IntArray] 再 `setPixels`，
 * 避免「先建 padding 整图、再裁剪」造成的一次额外整图分配与拷贝。
 * 实例内部复用缓冲，仅适用于单线程调用方（截图线程 / 帧源监听线程）。
 */
class ImageToBitmapConverter {

    private var scratch: IntArray? = null

    @Synchronized
    fun toBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val buffer = plane.buffer

        if (pixelStride == 4 && rowStride % 4 == 0) {
            buffer.rewind()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            if (rowStride == width * 4) {
                bitmap.copyPixelsFromBuffer(buffer)
            } else {
                val pixels = scratch?.takeIf { it.size >= width * height }
                    ?: IntArray(width * height).also { scratch = it }
                val rowInts = rowStride / 4
                val intBuffer = buffer.asIntBuffer()
                for (y in 0 until height) {
                    intBuffer.position(y * rowInts)
                    intBuffer.get(pixels, y * width, width)
                }
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            }
            return bitmap
        }

        // 非标准格式兜底：保留原有的 padding 整图 + 裁剪路径。
        buffer.rewind()
        val widthWithPadding = rowStride / pixelStride
        val padded = Bitmap.createBitmap(widthWithPadding, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (widthWithPadding == width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    /** 释放复用缓冲，供停止截图 / 关闭帧源时调用。 */
    @Synchronized
    fun clear() {
        scratch = null
    }
}
