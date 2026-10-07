package com.benton.izukijs.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.benton.izukijs.IzukiApp
import com.benton.izukijs.controller.GesturePoint
import com.benton.izukijs.controller.GestureStroke
import com.benton.izukijs.controller.accessibility.AccessibilityController
import com.benton.izukijs.model.ControlMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍控制后端。生效时向 [com.benton.izukijs.controller.ControllerManager] 注册/注销控制器。
 */
class IzukiAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        (application as? IzukiApp)?.container?.controllerManager?.register(
            AccessibilityController { instance }
        )
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        (application as? IzukiApp)?.container?.controllerManager?.unregister(
            ControlMode.ACCESSIBILITY
        )
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        (application as? IzukiApp)?.container?.controllerManager?.unregister(
            ControlMode.ACCESSIBILITY
        )
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** 程序化关闭无障碍服务（撤回权限）。 */
    fun disable() {
        runCatching { disableSelf() }
    }

    // ---- 供控制器调用的能力 ----

    fun performStrokes(strokes: List<GestureStroke>): Boolean {
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

    fun tapAt(x: Float, y: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
        return try {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (t: Throwable) {
            false
        }
    }

    fun swipeFromTo(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
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

    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun rootNode(): AccessibilityNodeInfo? = rootInActiveWindow

    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    fun screenshotBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        takeScreenshotInternal { bitmap ->
            result = bitmap
            latch.countDown()
        }
        return try {
            if (latch.await(5, TimeUnit.SECONDS)) result else null
        } catch (t: Throwable) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun takeScreenshotInternal(onResult: (Bitmap?) -> Unit) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                    val copy = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    hardwareBuffer.close()
                    onResult(copy)
                }

                override fun onFailure(errorCode: Int) {
                    onResult(null)
                }
            }
        )
    }

    companion object {
        @Volatile
        var instance: IzukiAccessibilityService? = null
            private set

        val isConnected: Boolean get() = instance != null
    }
}
