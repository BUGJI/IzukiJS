package com.benton.izukijs.virtualscreen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.benton.izukijs.core.log.LogBus
import java.util.concurrent.ConcurrentHashMap

/**
 * 把虚拟屏最新帧绘制到 App 内的一个或多个 SurfaceView。用于系统画中画与虚拟屏页面预览。
 *
 * Surface 只在本进程使用；虚拟屏本身由特权服务创建，预览面与采集面互不影响。
 * 多个预览面共享同一份最新帧，避免每帧重复做 Bitmap 拷贝。
 */
class VirtualScreenPreviewRenderer(
    private val logBus: LogBus,
) {

    private val thread = HandlerThread("izuki-vd-preview").apply { start() }
    private val handler = Handler(thread.looper)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val surfaces = ConcurrentHashMap<String, Surface>()

    @Volatile
    private var frameSource: VirtualScreenFrameSource? = null

    @Volatile
    private var running = false

    private val drawTask = object : Runnable {
        override fun run() {
            if (!running) return
            drawOnce()
            handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    /** 绑定一个预览面；同一 owner 再次绑定时替换旧 Surface。 */
    fun attach(owner: String, surface: Surface?) {
        if (surface == null) {
            surfaces.remove(owner)
        } else {
            surfaces[owner] = surface
        }
        if (surfaces.isEmpty()) {
            stop()
        } else {
            start()
        }
    }

    /** 只在 Surface 仍是该 owner 当前绑定的对象时解绑，避免误删新绑定的 Surface。 */
    fun detach(owner: String, surface: Surface?) {
        if (surface == null) {
            surfaces.remove(owner)
        } else {
            surfaces.remove(owner, surface)
        }
        if (surfaces.isEmpty()) {
            stop()
        }
    }

    fun setFrameSource(source: VirtualScreenFrameSource?) {
        frameSource = source
    }

    fun stop() {
        running = false
        handler.removeCallbacks(drawTask)
    }

    /** 停止后清掉最后一帧，避免 PiP 或页面里一直停在旧画面。 */
    fun clear() {
        surfaces.values.forEach { target ->
            drawBlack(target)
        }
    }

    fun close() {
        stop()
        surfaces.clear()
        frameSource = null
        runCatching { thread.quitSafely() }
    }

    private fun start() {
        if (running) return
        running = true
        handler.removeCallbacks(drawTask)
        handler.post(drawTask)
    }

    private fun drawOnce() {
        if (surfaces.isEmpty()) return
        val bitmap = frameSource?.capture() ?: return
        try {
            surfaces.values.forEach { target ->
                drawFitted(target, bitmap)
            }
        } catch (t: Throwable) {
            logBus.debug("虚拟屏预览绘制失败: ${t.message}")
            stop()
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawBlack(target: Surface) {
        val canvas = runCatching { target.lockCanvas(null) }.getOrNull() ?: return
        try {
            runCatching { canvas.drawColor(Color.BLACK) }
        } finally {
            runCatching { target.unlockCanvasAndPost(canvas) }
        }
    }

    private fun drawFitted(target: Surface, bitmap: Bitmap) {
        val canvas: Canvas = runCatching { target.lockCanvas(null) }.getOrNull() ?: return
        try {
            // 非 PiP 的宿主只有 1×1，跳过绘制省电；Surface 本身仍保持可用。
            if (canvas.width <= MIN_VISIBLE_SIZE || canvas.height <= MIN_VISIBLE_SIZE) return
            canvas.drawColor(Color.BLACK)
            val scale = minOf(
                canvas.width.toFloat() / bitmap.width,
                canvas.height.toFloat() / bitmap.height,
            )
            val drawWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val drawHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val left = (canvas.width - drawWidth) / 2
            val top = (canvas.height - drawHeight) / 2
            canvas.drawBitmap(
                bitmap,
                null,
                Rect(left, top, left + drawWidth, top + drawHeight),
                paint,
            )
        } finally {
            runCatching { target.unlockCanvasAndPost(canvas) }
        }
    }

    private companion object {
        const val FRAME_INTERVAL_MS = 66L
        const val MIN_VISIBLE_SIZE = 2
    }
}
