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
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import com.benton.izukijs.core.image.ImageToBitmapConverter
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.perf.PerfMetrics
import com.benton.izukijs.developer.DeveloperTuning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 基于 MediaProjection 的持续截图。用户授权一次后长期保持运行，
 * 后续截图无需弹窗，且没有无障碍 takeScreenshot 的频率/系统弹窗限制。
 *
 * 会在抓取时检测屏幕尺寸/方向变化（旋转/折叠），等旋转动画稳定后调整虚拟显示器。
 * 不立即 resize：动画期间的混合缓冲帧会把旧方向内容写进新尺寸缓冲区，表现为画面
 * 只占左上角、其余全黑。
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

    /** 当前虚拟显示器实际使用的几何；旋转/折叠后与 [pendingGeometry] 比较。 */
    @Volatile
    private var geometry: CaptureGeometry? = null

    /**
     * 已检测到但尚未应用的几何。
     *
     * 不立即 resize：屏幕旋转动画期间 SurfaceFlinger 仍会用旧方向的图层合成新尺寸的
     * 缓冲区，直接 resize 会缓存/保存出「内容只占左上角、其余全黑」的残帧。等旋转
     * 动画稳定后再调整虚拟显示器；窗口内返回空帧，由调用方回退到其它截图后端。
     */
    @Volatile
    private var pendingGeometry: CaptureGeometry? = null

    @Volatile
    private var pendingSinceElapsed = 0L

    /** 上一次主动读取屏幕几何的时间，用于在没有显示回调时兜底检测旋转。 */
    @Volatile
    private var lastGeometryCheckElapsed = 0L

    /** 最近一帧的副本：画面静止时 ImageReader 不会再产出帧，用它兜底避免重试 + 慢速回退。 */
    private var lastFrame: Bitmap? = null

    private data class CaptureGeometry(
        val width: Int,
        val height: Int,
        val density: Int,
        val rotation: Int,
    )

    @Volatile
    private var frameSeqValue = 0L

    /** 每次 resize / 重建自增；用于识别跨越 resize 的旧帧，避免把旧尺寸画面写回缓存。 */
    @Volatile
    private var geometryGeneration = 0L

    private val captureLock = Any()

    private val imageConverter = ImageToBitmapConverter()

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var displayManager: DisplayManager? = null

    /** 屏幕旋转 / 折叠变化时延迟调整虚拟显示器，取代每帧查询屏幕尺寸。 */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            detectGeometryChange(force = true)
        }
    }

    /** 旋转动画结束后真正调整虚拟显示器；提前 / 重复触发都是安全的。 */
    private val applyPendingGeometry = Runnable { applyPendingGeometryIfDue() }

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

            val initial = currentGeometry(appCtx)
            mediaProjection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        logBus.warn("录屏已由系统停止")
                        stop()
                    }
                },
                Handler(Looper.getMainLooper()),
            )

            synchronized(captureLock) {
                createDisplayLocked(mediaProjection, initial)
            }

            displayManager = appCtx.getSystemService(DisplayManager::class.java)?.also {
                it.registerDisplayListener(displayListener, mainHandler)
            }

            _active.value = true
            logBus.success(
                "持续录屏已启动（${initial.width} x ${initial.height}，rotation=${initial.rotation}）",
            )
        }.onFailure {
            logBus.error("启动录屏失败: ${it.message}")
            stop()
        }
    }

    /** 抓取最近一帧屏幕位图（调用方负责 recycle）。 */
    fun capture(): Bitmap? = capture(waitForNewFrame = false, waitTimeoutMs = 0L)

    /**
     * 抓取屏幕位图。
     *
     * [waitForNewFrame] 为 true 时在 [waitTimeoutMs] 内等待 ImageReader 产出一帧新画面，
     * 避免页面刚切换时直接返回上一页的缓存帧；超时后仍回退到缓存帧。
     */
    fun capture(waitForNewFrame: Boolean, waitTimeoutMs: Long): Bitmap? =
        PerfMetrics.measure("capture.screen") {
        // 兜底检测旋转（显示回调偶尔会读到旧尺寸或漏发）。
        detectGeometryChange(force = false)
        // 旋转动画期间不返回 MediaProjection 帧：SurfaceFlinger 仍会把旧方向的图层按
        // 1:1 写进新尺寸缓冲区，表现为画面只占一角、其余全黑。调用方会回退到
        // 无障碍 / Shell 截图，拿到完整画面；动画结束后再切回持续录屏。
        if (!ensureGeometryApplied()) {
            PerfMetrics.count("capture.screen.rotating")
            return@measure null
        }
        val (reader, generation, expected) = synchronized(captureLock) {
            Triple(imageReader, geometryGeneration, geometry)
        }
        if (reader == null) {
            PerfMetrics.count("capture.screen.rotating")
            return@measure null
        }
        // 已有缓存时不再为等新帧阻塞：有新帧就取，没有就立刻返回上一帧。
        val waitForFirstFrame = synchronized(captureLock) { lastFrame == null }
        // 等待新帧的 retry-sleep 与位图转换都不在锁内，避免阻塞主线程的 stop()/resize()。
        val retries = if (waitForFirstFrame) DeveloperTuning.current.firstCaptureRetry else 1
        val image = acquireLatest(reader, retries, waitForNewFrame, waitTimeoutMs)
        if (image == null) {
            PerfMetrics.count("capture.screen.cached")
            return@measure cachedCopy()
        }
        // resize 期间可能拿到旧尺寸的缓冲；尺寸不匹配时丢弃，交给调用方回退截图。
        val imageMatches = expected != null &&
            image.width == expected.width && image.height == expected.height
        if (!imageMatches) {
            runCatching { image.close() }
            PerfMetrics.count("capture.screen.rotating")
            return@measure null
        }
        val bitmap = try {
            imageConverter.toBitmap(image)
        } catch (t: Throwable) {
            PerfMetrics.count("capture.screen.error")
            logBus.warn("屏幕帧转换失败：${t.message}")
            null
        } finally {
            runCatching { image.close() }
        }
        if (bitmap == null) {
            PerfMetrics.count("capture.screen.cached")
            cachedCopy()
        } else {
            val accepted = synchronized(captureLock) {
                if (generation != geometryGeneration) {
                    false
                } else {
                    frameSeqValue++
                    // 调用方会 recycle 返回值，这里保留一份副本供静态画面兜底。
                    updateCache(bitmap)
                    true
                }
            }
            if (accepted) {
                bitmap
            } else {
                // resize 在抓取期间发生：这张图属于旧几何，不能返回给调用方。
                runCatching { bitmap.recycle() }
                PerfMetrics.count("capture.screen.rotating")
                null
            }
        }
    }

    /** 最近一帧的序号；每成功抓取一帧自增，可用于判断画面是否更新。 */
    fun frameSeq(): Long = frameSeqValue

    private fun updateCache(bitmap: Bitmap) {
        lastFrame?.recycle()
        lastFrame = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
    }

    private fun cachedCopy(): Bitmap? = synchronized(captureLock) {
        lastFrame?.takeIf { !it.isRecycled }?.let { frame ->
            runCatching { frame.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        }
    }

    fun stop() {
        mainHandler.removeCallbacks(applyPendingGeometry)
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
            geometry = null
            pendingGeometry = null
            pendingSinceElapsed = 0L
            lastGeometryCheckElapsed = 0L
            lastFrame?.recycle()
            lastFrame = null
            frameSeqValue = 0L
            geometryGeneration++
        }
        imageConverter.clear()
    }

    /** 首次创建；MediaProjection 同一个 token 只允许创建一次虚拟显示器。 */
    private fun createDisplayLocked(projection: MediaProjection, target: CaptureGeometry) {
        val reader = ImageReader.newInstance(
            target.width,
            target.height,
            PixelFormat.RGBA_8888,
            2,
        )
        val display = try {
            projection.createVirtualDisplay(
                "izuki-capture",
                target.width,
                target.height,
                target.density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null,
            )
        } catch (t: Throwable) {
            runCatching { reader.close() }
            throw t
        }
        imageReader = reader
        virtualDisplay = display
        geometry = target
        geometryGeneration++
        pendingGeometry = null
        pendingSinceElapsed = 0L
    }

    /**
     * 按新几何调整已有的虚拟显示器。
     *
     * MediaProjection 在 Android 14+ 不允许对同一个 token 重复 `createVirtualDisplay`，
     * 因此只能 `resize`。为避免 resize 与 Surface 切换不是原子操作而产生的混合缓冲帧，
     * 先摘下 Surface，resize 完成后再挂上新尺寸的 ImageReader。
     */
    private fun resizeDisplayLocked(target: CaptureGeometry) {
        val display = virtualDisplay ?: return
        val oldReader = imageReader
        lastFrame?.recycle()
        lastFrame = null

        // 先断开旧 Surface，确保 resize 期间不会再有旧尺寸的帧写入新缓冲区。
        runCatching { display.surface = null }
        val resizeFailure = runCatching {
            display.resize(target.width, target.height, target.density)
        }.exceptionOrNull()
        if (resizeFailure != null) {
            logBus.warn("录屏 resize 失败：${resizeFailure.message}")
            // 保留旧尺寸继续工作，稍后再重试，避免整个录屏链路断掉。
            runCatching { oldReader?.surface?.let { display.surface = it } }
            imageReader = oldReader
            return
        }

        val newReader = ImageReader.newInstance(
            target.width,
            target.height,
            PixelFormat.RGBA_8888,
            2,
        )
        imageReader = newReader
        val attachFailure = runCatching { display.surface = newReader.surface }
            .exceptionOrNull()
        if (attachFailure != null) {
            logBus.warn("录屏重新挂载 Surface 失败：${attachFailure.message}")
            runCatching { newReader.close() }
            // 尽量恢复旧 Surface；若旧 reader 已关闭则保持空 Surface 等待下次重试。
            imageReader = oldReader
            runCatching { oldReader?.surface?.let { display.surface = it } }
            return
        }
        runCatching { oldReader?.close() }

        geometry = target
        geometryGeneration++
        pendingGeometry = null
        pendingSinceElapsed = 0L
    }

    /**
     * 检测屏幕几何变化并安排旋转动画结束后的 resize。
     *
     * 显示回调可能读到旋转前的旧尺寸，而单纯依赖回调也可能漏事件；因此 [capture]
     * 会以较低频率主动兜底检测。重复通知在 [geometry] / [pendingGeometry] 处合并。
     */
    private fun detectGeometryChange(force: Boolean) {
        val context = appContext ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastGeometryCheckElapsed < GEOMETRY_CHECK_INTERVAL_MS) return
        lastGeometryCheckElapsed = now
        val latest = currentGeometry(context)
        synchronized(captureLock) {
            if (latest == geometry) return
            if (latest == pendingGeometry) return
            pendingGeometry = latest
            pendingSinceElapsed = now
        }
        mainHandler.removeCallbacks(applyPendingGeometry)
        mainHandler.postDelayed(applyPendingGeometry, ROTATION_SETTLE_MS)
    }

    /** 几何已应用返回 true；仍处于旋转稳定窗口内返回 false（调用方应回退截图）。 */
    private fun ensureGeometryApplied(): Boolean {
        val waitMs = synchronized(captureLock) {
            if (pendingGeometry == null) return true
            val dueAt = pendingSinceElapsed + ROTATION_SETTLE_MS
            dueAt - SystemClock.elapsedRealtime()
        }
        if (waitMs > 0L) return false
        applyPendingGeometryIfDue()
        return synchronized(captureLock) { pendingGeometry == null }
    }

    private fun applyPendingGeometryIfDue() {
        var applied: CaptureGeometry? = null
        var needsRetry = false
        synchronized(captureLock) {
            val pending = pendingGeometry ?: return
            if (!_active.value) return
            if (SystemClock.elapsedRealtime() < pendingSinceElapsed + ROTATION_SETTLE_MS) return
            if (virtualDisplay == null) {
                pendingGeometry = null
                return
            }
            runCatching { resizeDisplayLocked(pending) }
                .onFailure { logBus.warn("录屏调整尺寸失败：${it.message}") }
            lastGeometryCheckElapsed = SystemClock.elapsedRealtime()
            if (pendingGeometry == null) {
                applied = geometry
            } else {
                needsRetry = true
            }
        }
        // resize / 挂载失败时 pendingGeometry 仍保留，稍后自动重试。
        if (needsRetry) {
            mainHandler.removeCallbacks(applyPendingGeometry)
            mainHandler.postDelayed(applyPendingGeometry, ROTATION_RETRY_MS)
        }
        applied?.let {
            logBus.info(
                "录屏尺寸已更新为 ${it.width} x ${it.height}（rotation=${it.rotation}）",
            )
        }
    }

    private fun acquireLatest(
        reader: ImageReader,
        retries: Int,
        waitForNewFrame: Boolean,
        waitTimeoutMs: Long,
    ): Image? {
        val deadline = if (waitForNewFrame && waitTimeoutMs > 0L) {
            SystemClock.elapsedRealtime() + waitTimeoutMs
        } else {
            0L
        }
        var attempt = 0
        while (true) {
            val image = runCatching { reader.acquireLatestImage() }.getOrNull()
            if (image != null) return image
            if (deadline > 0L) {
                if (SystemClock.elapsedRealtime() >= deadline) return null
                runCatching { Thread.sleep(FRESH_FRAME_RETRY_MS) }
            } else {
                if (attempt >= retries - 1) return null
                attempt++
                runCatching {
                    Thread.sleep(DeveloperTuning.current.captureRetryDelayMs.toLong())
                }
            }
        }
    }

    private fun currentGeometry(context: Context): CaptureGeometry {
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
        return CaptureGeometry(
            width = metrics.widthPixels.coerceAtLeast(1),
            height = metrics.heightPixels.coerceAtLeast(1),
            density = metrics.densityDpi.coerceAtLeast(1),
            rotation = runCatching { display?.rotation ?: 0 }.getOrDefault(0),
        )
    }

    private companion object {
        /**
         * 旋转动画稳定窗口。旋转期间立即 resize 会拿到旧方向内容塞进新尺寸缓冲区的
         * 残帧；窗口内截图请求会返回空帧，由调用方回退到无障碍 / Shell 截图。
         */
        const val ROTATION_SETTLE_MS = 2500L

        /** resize / Surface 挂载失败时的重试间隔。 */
        const val ROTATION_RETRY_MS = 300L

        /** 无显示回调时，[capture] 主动兜底检测几何变化的最小间隔。 */
        const val GEOMETRY_CHECK_INTERVAL_MS = 400L

        /** 等待新画面时的重试间隔；约一帧时间，避免过度占用 CPU。 */
        const val FRESH_FRAME_RETRY_MS = 16L
    }
}
