package com.benton.izukijs.service

import android.os.Handler
import android.os.Looper
import android.view.View
import com.benton.izukijs.core.perf.PerfMetrics
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 截图 / 触摸注入时临时隐藏悬浮层的中枢。
 *
 * 悬浮窗服务注册 [Listener]；脚本执行任何截图（找图 / 找色 / OCR / captureScreen / AI）
 * 或触摸注入（点击 / 长按 / 滑动 / 手势）前调用 [withoutOverlay]，先隐藏悬浮窗并等待一帧，
 * 操作完成后再恢复显示：既避免悬浮窗内容被截入画面，也避免点击落在悬浮条上而非目标应用。
 * 没有注册监听器时该调用无任何开销。
 *
 * 支持同时注册多个监听者（脚本日志悬浮窗、调试面板等），隐藏 / 恢复会作用于全部监听者。
 * 连续操作（例如循环找图或连点）不会每次都重新隐藏 / 等待：隐藏状态会保持到最后一帧后
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

    private val listeners = LinkedHashSet<Listener>()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前是否处于隐藏状态。 */
    private var hidden = false

    private val showRunnable = Runnable {
        val snapshot = synchronized(LOCK) {
            if (!hidden) return@synchronized null
            hidden = false
            listeners.toList()
        } ?: return@Runnable
        snapshot.forEach { it.show() }
    }

    fun register(value: Listener) {
        val hideNow = synchronized(LOCK) {
            listeners.add(value)
            hidden
        }
        if (hideNow) value.hide()
    }

    fun unregister(value: Listener) {
        synchronized(LOCK) {
            listeners.remove(value)
            if (listeners.isEmpty()) {
                mainHandler.removeCallbacks(showRunnable)
                hidden = false
            }
        }
    }

    /**
     * 在截图 / 触摸注入前后临时隐藏 / 显示悬浮层。应在非主线程调用（操作发生在脚本线程）。
     */
    fun <T> withoutOverlay(block: () -> T): T {
        var needWait = false
        val targets: List<Listener>
        synchronized(LOCK) {
            targets = listeners.toList()
            if (targets.isNotEmpty() && !hidden) {
                hidden = true
                needWait = true
                targets.forEach { it.hide() }
            }
        }
        if (targets.isEmpty()) return block()
        if (needWait) {
            PerfMetrics.measure("overlay.hideWait") {
                targets.forEach { it.awaitHidden(HIDE_TIMEOUT_MS) }
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

    /**
     * 等待视图在隐藏后真正完成一帧绘制。主线程调用或视图未附着时立即返回，避免阻塞主线程。
     */
    fun awaitViewHidden(view: View, timeoutMs: Long) {
        if (Looper.myLooper() == Looper.getMainLooper() || !view.isAttachedToWindow) return
        val latch = CountDownLatch(1)
        view.post {
            if (!view.isAttachedToWindow) {
                latch.countDown()
                return@post
            }
            // 等两帧：第一帧完成隐藏后的 traversal / draw，第二帧确认画面已更新。
            view.postOnAnimation {
                view.postOnAnimation { latch.countDown() }
            }
        }
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** 隐藏后等待一帧绘制的最长时间；实现方通常在一帧内返回。 */
    private const val HIDE_TIMEOUT_MS = 100L

    /** 连续操作结束后延迟恢复显示，避免高频操作时反复闪烁。 */
    private const val SHOW_DELAY_MS = 300L
}
