package com.benton.izukijs.controller

import android.graphics.Bitmap
import com.benton.izukijs.developer.DeveloperTuning

/**
 * 轻量帧差探测：连续两帧抽样像素变化明显时，判定界面仍在动画中。
 *
 * 用于在 `uiautomator dump` 前快速跳过必然等不到 idle 的界面，避免固定 6s 超时。
 */
object ScreenAnimationProbe {

    fun isAnimating(
        capture: () -> Bitmap?,
        intervalMs: Long = DeveloperTuning.current.animationProbeIntervalMs.toLong(),
        cacheMs: Long = DeveloperTuning.current.animationProbeCacheMs.toLong(),
    ): Boolean {
        val now = System.currentTimeMillis()
        val cached = lastResult
        if (cached != null && now - lastProbeAt < cacheMs) return cached
        val first = capture() ?: return false.also { remember(false) }
        val firstSamples = try {
            sample(first)
        } finally {
            first.recycle()
        }
        Thread.sleep(intervalMs.coerceAtLeast(0L))
        val second = capture() ?: return false.also { remember(false) }
        val secondSamples = try {
            sample(second)
        } finally {
            second.recycle()
        }
        var changed = 0
        for (index in firstSamples.indices) {
            if (firstSamples[index] != secondSamples[index]) changed++
        }
        val animating = changed >= maxOf(MIN_CHANGED_SAMPLES, firstSamples.size / CHANGE_DIVISOR)
        remember(animating)
        return animating
    }

    private fun sample(bitmap: Bitmap): IntArray {
        val columns = SAMPLE_COLUMNS
        val rows = SAMPLE_ROWS
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

    @Synchronized
    private fun remember(value: Boolean) {
        lastResult = value
        lastProbeAt = System.currentTimeMillis()
    }

    @Volatile
    private var lastResult: Boolean? = null

    @Volatile
    private var lastProbeAt = 0L

    private const val SAMPLE_COLUMNS = 24
    private const val SAMPLE_ROWS = 32
    private const val MIN_CHANGED_SAMPLES = 4
    private const val CHANGE_DIVISOR = 200
}
