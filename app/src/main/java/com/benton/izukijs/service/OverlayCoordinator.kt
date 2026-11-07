package com.benton.izukijs.service

import android.os.Handler
import android.os.Looper
import com.benton.izukijs.core.perf.PerfMetrics

/**
 * 截图时临时隐藏悬浮层的中枢。
 *
 * 悬浮窗服务注册 [Listener]；脚本执行任何截图（找图 / 找色 / OCR / captureScreen / AI）前
 * 调用 [withoutOverlay]，先隐藏悬浮窗并等待一帧，截图完成后再恢复显示，
 * 避免悬浮窗内容被截入画面。没有注册监听器时该调用无任何开销。
 *
 * 连续截图（例如循环找图）不会每次都重新隐藏 / 等待：隐藏状态会保持到最后一帧后
 * 延迟 [SHOW_DELAY_MS] 再恢复，兼顾正确性与性能。
 */
object OverlayCoordinator {

    interface Listener {
        fun hide()

        fun show()

        /**
         * 在 [hide] 之后等待悬浮层真正从画面消失。
         *
         * 默认退化为固定睡眠；悬浮窗实现可改为等待一帧绘制完成，通常比固定睡眠更快。
         */
        fun awaitHidden(timeoutMs: Long) {
            try {
                Thread.sleep(timeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private val LOCK = Any()

    @Volatile
    private var listener: Listener? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前是否处于隐藏状态。 */
    private var hidden = false

    private val showRunnable = Runnable {
        synchronized(LOCK) {
            if (!hidden) return@synchronized
            hidden = false
            listener?.show()
        }
    }

    fun register(value: Listener) {
        synchronized(LOCK) { listener = value }
    }

    fun unregister(value: Listener) {
        synchronized(LOCK) {
            if (listener === value) {
                listener = null
                mainHandler.removeCallbacks(showRunnable)
                hidden = false
            }
        }
    }

    /**
     * 在截图前后临时隐藏 / 显示悬浮窗。应在非主线程调用（截图发生在脚本线程）。
     */
    fun <T> withoutOverlay(block: () -> T): T {
        var current: Listener? = null
        var needWait = false
        synchronized(LOCK) {
            val target = listener
            current = target
            if (target != null && !hidden) {
                hidden = true
                needWait = true
                target.hide()
            }
        }
        val target = current ?: return block()
        if (needWait) {
            PerfMetrics.measure("overlay.hideWait") {
                target.awaitHidden(HIDE_TIMEOUT_MS)
            }
        }
        return try {
            block()
        } finally {
            synchronized(LOCK) {
                if (hidden) {
                    mainHandler.removeCallbacks(showRunnable)
                    mainHandler.postDelayed(showRunnable, SHOW_DELAY_MS)
                }
            }
        }
    }

    /** 隐藏后等待一帧绘制的最长时间；实现方通常在一帧内返回。 */
    private const val HIDE_TIMEOUT_MS = 100L

    /** 连续截图结束后延迟恢复显示，避免高频截图时反复闪烁。 */
    private const val SHOW_DELAY_MS = 300L
}
