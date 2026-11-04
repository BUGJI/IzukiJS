package com.benton.izukijs.virtualscreen

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.benton.izukijs.core.log.LogBus
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 虚拟屏帧源。ImageReader 的 Surface 交给 Shizuku 特权服务创建 VirtualDisplay，
 * 帧直接落在 App 进程，截图与 PiP 预览都不需要跨进程传大图。
 */
class VirtualScreenFrameSource(
    private val width: Int,
    private val height: Int,
    private val logBus: LogBus,
) {

    private val thread = HandlerThread("izuki-vd-frames").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = ReentrantLock()
    private val frameAvailable = lock.newCondition()

    private var reader: ImageReader? = null
    private var latest: Bitmap? = null
    private var frameSeq = 0L
    private var closed = false

    /** 只在监听线程读写；限制位图转换频率，避免 60fps 全量拷贝拖垮 CPU。 */
    private var lastPublishedAtMs = 0L

    val surface: Surface
        get() = requireNotNull(reader?.surface) { "frame source not started" }

    fun start() {
        if (reader != null || closed) return
        val created = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        reader = created
        created.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull()
                ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastPublishedAtMs < MIN_FRAME_INTERVAL_MS) {
                    return@setOnImageAvailableListener
                }
                val bitmap = runCatching { imageToBitmap(image) }.getOrNull()
                if (bitmap != null) {
                    lastPublishedAtMs = now
                    publish(bitmap)
                }
            } finally {
                runCatching { image.close() }
            }
        }, handler)
        logBus.debug("虚拟屏帧源已启动（${width}x$height）")
    }

    /**
     * 等待比 [afterSeq] 更新的帧；[afterSeq] 为 null 时直接取当前最新帧。
     * 返回的 Bitmap 归调用方所有，调用方负责 recycle。
     */
    fun capture(afterSeq: Long? = null, timeoutMs: Long = 0L): Bitmap? = lock.withLock {
        if (afterSeq != null && frameSeq <= afterSeq && timeoutMs > 0L) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (frameSeq <= afterSeq && !closed) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                frameAvailable.await(remaining, TimeUnit.MILLISECONDS)
            }
        }
        latest?.copy(Bitmap.Config.ARGB_8888, false)
    }

    /**
     * 取最新帧；[waitMs] > 0 且尚未收到任何帧时，最多等待这么久。
     * 返回的 Bitmap 归调用方所有，调用方负责 recycle。
     */
    fun captureLatest(waitMs: Long = 0L): Bitmap? = lock.withLock {
        if (latest == null && waitMs > 0L) {
            awaitLatestLocked(waitMs)
        }
        latest?.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** 等待首帧；已有帧时立即返回 true。 */
    fun awaitFrame(timeoutMs: Long): Boolean = lock.withLock {
        if (latest == null && timeoutMs > 0L) {
            awaitLatestLocked(timeoutMs)
        }
        latest != null
    }

    private fun awaitLatestLocked(timeoutMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (latest == null && !closed) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) break
            frameAvailable.await(remaining, TimeUnit.MILLISECONDS)
        }
    }

    fun currentSeq(): Long = lock.withLock { frameSeq }

    fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            latest?.recycle()
            latest = null
            frameAvailable.signalAll()
        }
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { reader?.close() }
        reader = null
        runCatching { thread.quitSafely() }
    }

    private fun publish(bitmap: Bitmap) {
        lock.withLock {
            if (closed) {
                bitmap.recycle()
                return
            }
            val old = latest
            latest = bitmap
            frameSeq++
            old?.recycle()
            frameAvailable.signalAll()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val widthWithPadding = image.width + rowPadding / pixelStride
        val full = Bitmap.createBitmap(widthWithPadding, image.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(plane.buffer)
        if (widthWithPadding == image.width) return full
        val cropped = Bitmap.createBitmap(full, 0, 0, image.width, image.height)
        full.recycle()
        return cropped
    }

    private companion object {
        const val MAX_IMAGES = 3

        /** 约 25fps；截图/OCR 足够，同时避免每帧都做一次全量 RGBA 拷贝。 */
        const val MIN_FRAME_INTERVAL_MS = 40L
    }
}
