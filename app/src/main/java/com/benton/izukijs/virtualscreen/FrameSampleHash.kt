package com.benton.izukijs.virtualscreen

import android.graphics.Bitmap

/**
 * 稀疏帧签名：按网格抽样像素并混合为 64 位哈希。
 *
 * 用于判断界面是否已经停止变化；只比较抽样点，避免每次稳定帧检测都做整屏比较。
 */
internal object FrameSampleHash {

    fun sample(bitmap: Bitmap): Long = hash(samples(bitmap), bitmap.width, bitmap.height)

    fun samples(bitmap: Bitmap, columns: Int = COLUMNS, rows: Int = ROWS): IntArray {
        val out = IntArray(columns * rows)
        var index = 0
        for (row in 0 until rows) {
            val y = ((row + 0.5) * bitmap.height / rows).toInt().coerceIn(0, bitmap.height - 1)
            for (column in 0 until columns) {
                val x = ((column + 0.5) * bitmap.width / columns).toInt().coerceIn(0, bitmap.width - 1)
                out[index++] = bitmap.getPixel(x, y)
            }
        }
        return out
    }

    fun hash(samples: IntArray, width: Int, height: Int): Long {
        var hash = 1125899906842597L
        hash = hash * 31 + width
        hash = hash * 31 + height
        for (sample in samples) {
            hash = hash * 31 + sample
        }
        return hash
    }

    const val COLUMNS = 16
    const val ROWS = 16
}
