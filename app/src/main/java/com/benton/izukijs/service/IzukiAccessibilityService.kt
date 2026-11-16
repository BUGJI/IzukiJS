package com.benton.izukijs.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.benton.izukijs.IzukiApp
import com.benton.izukijs.controller.GesturePoint
import com.benton.izukijs.controller.GestureStroke
import com.benton.izukijs.controller.accessibility.AccessibilityController
import com.benton.izukijs.controller.accessibility.AccessibilityServiceHandle
import com.benton.izukijs.model.ControlMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍控制后端。生效时向 [com.benton.izukijs.controller.ControllerManager] 注册/注销控制器。
 */
class IzukiAccessibilityService : AccessibilityService(), AccessibilityServiceHandle {

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onServiceConnected() {
        setInstance(this)
        ToastObserver.setConnected(true)
        (application as? IzukiApp)?.container?.controllerManager?.register(
            AccessibilityController { instance }
        )
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        (application as? IzukiApp)?.container?.controllerManager?.unregister(
            ControlMode.ACCESSIBILITY
        )
        ToastObserver.setConnected(false)
        setInstance(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        (application as? IzukiApp)?.container?.controllerManager?.unregister(
            ControlMode.ACCESSIBILITY
        )
        ToastObserver.setConnected(false)
        setInstance(null)
        super.onDestroy()
    }

    /**
     * 从无障碍事件中提取 toast。
     *
     * 系统通知也走 `TYPE_NOTIFICATION_STATE_CHANGED`，但 `parcelableData` 是
     * [Notification]；只有不是通知的事件才当作 toast。解析失败时静默忽略，
     * 不影响无障碍控制本身。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventPackage = event.packageName?.toString()
        val className = event.className?.toString()
        val texts = event.text.map { it?.toString() }.filterNotNull()
        val joinedText = texts.joinToString(" ")
        val contentDescription = event.contentDescription?.toString()
        val parcelableIsNotification = runCatching {
            event.parcelableData is Notification
        }.getOrDefault(false)

        val eventType = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window_state"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "window_content"
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "windows_changed"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "view_scrolled"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "view_clicked"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "text_changed"
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                if (parcelableIsNotification) "notification" else "toast"
            else -> null
        }
        if (eventType != null) {
            AccessibilityEventStore.record(
                type = eventType,
                packageName = eventPackage,
                className = className,
                text = joinedText,
                contentDescription = contentDescription,
                windowId = event.windowId,
                time = event.eventTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
            )
        }

        if (event.eventType != ToastRules.TYPE_NOTIFICATION_STATE_CHANGED) return
        ToastObserver.recordFrom(
            eventType = event.eventType,
            parcelableIsNotification = parcelableIsNotification,
            packageName = eventPackage,
            ownPackage = packageName,
            texts = event.text.map { it?.toString() },
            className = className,
        )
    }

    /**
     * 按键只记录不拦截：返回 false 让系统继续处理按键。
     *
     * 需要在无障碍配置里声明 `flagRequestFilterKeyEvents` 才能收到回调。
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        val keyEvent = event ?: return false
        val type = when (keyEvent.action) {
            KeyEvent.ACTION_DOWN -> "key_down"
            KeyEvent.ACTION_UP -> "key_up"
            else -> return false
        }
        AccessibilityEventStore.record(
            type = type,
            text = KeyEvent.keyCodeToString(keyEvent.keyCode),
            time = keyEvent.eventTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
            keyCode = keyEvent.keyCode,
        )
        return false
    }

    override fun onInterrupt() = Unit

    /** 程序化关闭无障碍服务（撤回权限）。 */
    fun disable() {
        runCatching { disableSelf() }
    }

    // ---- 供控制器调用的能力 ----

    override fun performStrokes(strokes: List<GestureStroke>): Boolean {
        if (strokes.isEmpty()) return false
        val builder = GestureDescription.Builder()
        var strokeCount = 0
        for (stroke in strokes) {
            strokeCount += appendStroke(builder, stroke)
        }
        if (strokeCount == 0) return false
        return try {
            dispatchGesture(builder.build(), null, null)
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 把一条笔画拆成「移动段 + 停顿段」，用 willContinue / continueStroke 串联到同一根手指，
     * 从而支持中途或终点按住停顿。返回实际加入的 Stroke 数。
     *
     * 关键：同一次手势内，后继 Stroke 的 startTime 必须等于前一段的结束时间（累加），
     * 否则各段会在时间轴上重叠，整条手势会退化成在终点长按。
     */
    private fun appendStroke(
        builder: GestureDescription.Builder,
        stroke: GestureStroke,
    ): Int {
        val points = stroke.points
        if (points.size < 2) return 0
        val segments = buildSegments(points)
        var previous: GestureDescription.StrokeDescription? = null
        var startTime = 0L
        for ((index, segment) in segments.withIndex()) {
            val (path, durationMs) = segment
            val willContinue = index < segments.lastIndex
            val duration = durationMs.coerceAtLeast(1L)
            val description = previous?.continueStroke(path, startTime, duration, willContinue)
                ?: GestureDescription.StrokeDescription(path, startTime, duration, willContinue)
            builder.addStroke(description)
            previous = description
            startTime += duration
        }
        return segments.size
    }

    /**
     * 将带时间戳的点序列切成移动段（折线）与停顿段（原地停留）。
     * 相邻移动段合并为一条折线，避免超过系统 Stroke 数量上限；相邻停顿段合并时长。
     */
    private fun buildSegments(points: List<GesturePoint>): List<Pair<Path, Long>> {
        val segments = ArrayList<Pair<Path, Long>>()
        var runPath = Path().apply { moveTo(points[0].x, points[0].y) }
        var runDuration = 0L
        var runIsHold = true
        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val current = points[i]
            val delta = (current.timeMs - previous.timeMs).coerceAtLeast(0L)
            val holding = previous.x == current.x && previous.y == current.y
            if (holding) {
                if (!runIsHold) {
                    segments.add(runPath to runDuration.coerceAtLeast(1L))
                    runPath = Path().apply { moveTo(previous.x, previous.y) }
                    runDuration = 0L
                    runIsHold = true
                }
                runDuration += delta
            } else {
                if (runIsHold && runDuration > 0L) {
                    segments.add(runPath to runDuration.coerceAtLeast(1L))
                    runPath = Path().apply { moveTo(previous.x, previous.y) }
                    runDuration = 0L
                }
                runPath.lineTo(current.x, current.y)
                runDuration += delta
                runIsHold = false
            }
        }
        if (runDuration > 0L || !runIsHold) {
            segments.add(runPath to runDuration.coerceAtLeast(1L))
        }
        return segments
    }

    override fun tapAt(x: Float, y: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
        return try {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (t: Throwable) {
            false
        }
    }

    override fun swipeFromTo(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
        return try {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (t: Throwable) {
            false
        }
    }

    override fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun hasEditableFocus(): Boolean? {
        val root = rootInActiveWindow ?: return false
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true
    }

    override fun rootNode(): AccessibilityNodeInfo? = rootInActiveWindow

    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    override fun screenshotBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        runCatching {
            takeScreenshotInternal(attempt = 0) { bitmap ->
                result = bitmap
                latch.countDown()
            }
        }.onFailure {
            latch.countDown()
        }
        return try {
            if (latch.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) result else null
        } catch (t: Throwable) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun takeScreenshotInternal(attempt: Int, onResult: (Bitmap?) -> Unit) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                    // 硬件位图只是包装，复制成 ARGB 后及时回收包装，避免每次截图都遗留一张。
                    val copy = try {
                        bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        bitmap?.recycle()
                        hardwareBuffer.close()
                    }
                    onResult(copy)
                }

                override fun onFailure(errorCode: Int) {
                    // 系统对无障碍截图有频率限制，连续调用时短暂等待后重试。
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT &&
                        attempt < MAX_SCREENSHOT_RETRIES
                    ) {
                        mainHandler.postDelayed(
                            { takeScreenshotInternal(attempt + 1, onResult) },
                            SCREENSHOT_RETRY_DELAY_MS,
                        )
                    } else {
                        onResult(null)
                    }
                }
            },
        )
    }

    companion object {
        /** 无障碍截图等待回调的上限；覆盖 60 次限流重试。 */
        private const val SCREENSHOT_TIMEOUT_MS = 5_000L
        private const val MAX_SCREENSHOT_RETRIES = 60
        private const val SCREENSHOT_RETRY_DELAY_MS = 50L

        /**
         * 用弱引用持有已连接的服务实例，避免静态字段延长 Service（Context）生命周期；
         * 系统在绑定期间仍持有强引用，因此正常使用不会提前被回收。
         */
        @Volatile
        private var instanceRef: java.lang.ref.WeakReference<IzukiAccessibilityService>? = null

        val instance: IzukiAccessibilityService?
            get() = instanceRef?.get()

        val isConnected: Boolean get() = instance != null

        private fun setInstance(service: IzukiAccessibilityService?) {
            instanceRef = service?.let { java.lang.ref.WeakReference(it) }
        }
    }
}
