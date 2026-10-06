package com.benton.izukijs.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import com.benton.izukijs.runtime.LogBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 基于 MediaProjection 的持续截图。用户授权一次后长期保持运行，
 * 后续截图无需弹窗，且没有无障碍 takeScreenshot 的频率/系统弹窗限制。
 *
 * 会在抓取时检测屏幕尺寸变化（旋转/折叠），必要时自动重建虚拟显示器。
 */
class ScreenCapture(private val logBus: LogBus) {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var projection: MediaProjection? = null

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var imageReader: ImageReader? = null

    @Volatile
    private var width = 0

    @Volatile
    private var height = 0

    @Volatile
    private var density = 0

    private val captureLock = Any()

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var displayManager: DisplayManager? = null

    /** 屏幕旋转 / 折叠变化时重建虚拟显示器，取代每帧查询屏幕尺寸。 */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val context = appContext ?: return
            val projection = projection ?: return
            synchronized(captureLock) {
                val size = screenSize(context)
                if (size[0] != width || size[1] != height) {
                    resize(projection, size)
                }
            }
        }
    }

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    val isActive: Boolean get() = _active.value

    fun start(context: Context, resultCode: Int, data: Intent) {
        stop()
        runCatching {
            val appCtx = context.applicationContext
            val manager = appCtx.getSystemService(MediaProjectionManager::class.java)
            val mediaProjection = manager.getMediaProjection(resultCode, data) ?: return
            appContext = appCtx
            projection = mediaProjection

            val size = screenSize(appCtx)
            width = size[0]
            height = size[1]
            density = size[2]
            createDisplay(mediaProjection, width, height, density)

            mediaProjection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        logBus.warn("录屏已由系统停止")
                        stop()
                    }
                },
                Handler(Looper.getMainLooper()),
            )

            displayManager = appCtx.getSystemService(DisplayManager::class.java)?.also {
                it.registerDisplayListener(displayListener, mainHandler)
            }

            _active.value = true
            logBus.success("持续录屏已启动（$width x $height）")
        }.onFailure {
            logBus.error("启动录屏失败: ${it.message}")
            stop()
        }
    }

    /** 抓取最近一帧屏幕位图（调用方负责 recycle）。 */
    fun capture(): Bitmap? = synchronized(captureLock) {
        val reader = imageReader ?: return@synchronized null
        val image = acquireLatest(reader) ?: return@synchronized null
        try {
            imageToBitmap(image)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { image.close() }
        }
    }

    fun stop() {
        synchronized(captureLock) {
            _active.value = false
            runCatching { displayManager?.unregisterDisplayListener(displayListener) }
            runCatching { virtualDisplay?.release() }
            runCatching { imageReader?.close() }
            runCatching { projection?.stop() }
            displayManager = null
            virtualDisplay = null
            imageReader = null
            projection = null
            appContext = null
            width = 0
            height = 0
            density = 0
        }
    }

    private fun createDisplay(projection: MediaProjection, w: Int, h: Int, d: Int) {
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = projection.createVirtualDisplay(
            "izuki-capture",
            w,
            h,
            d,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null,
        )
    }

    private fun resize(projection: MediaProjection, size: IntArray) {
        width = size[0]
        height = size[1]
        density = size[2]
        val oldReader = imageReader
        val display = virtualDisplay
        if (display != null) {
            val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = newReader
            runCatching {
                display.resize(width, height, density)
                display.surface = newReader.surface
            }.onFailure {
                runCatching { newReader.close() }
                createDisplay(projection, width, height, density)
            }
        } else {
            createDisplay(projection, width, height, density)
        }
        runCatching { oldReader?.close() }
        logBus.info("录屏尺寸已更新为 ${width} x ${height}")
    }

    private fun acquireLatest(reader: ImageReader): Image? {
        repeat(CAPTURE_RETRY) {
            val image = runCatching { reader.acquireLatestImage() }.getOrNull()
            if (image != null) return image
            runCatching { Thread.sleep(CAPTURE_RETRY_DELAY_MS) }
        }
        return null
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes[0]
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

    private fun screenSize(context: Context): IntArray {
        val metrics = DisplayMetrics()
        val display = runCatching {
            context.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull()
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        } else {
            metrics.setTo(context.resources.displayMetrics)
        }
        return intArrayOf(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private companion object {
        const val CAPTURE_RETRY = 4
        const val CAPTURE_RETRY_DELAY_MS = 15L
    }
}
