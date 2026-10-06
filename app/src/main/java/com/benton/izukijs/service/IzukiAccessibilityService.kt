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
        for (stroke in strokes) {
            if (stroke.points.size < 2) continue
            val path = Path()
            val first = stroke.points.first()
            path.moveTo(first.x, first.y)
            for (point in stroke.points.drop(1)) {
                path.lineTo(point.x, point.y)
            }
            val duration = (stroke.points.last().timeMs - first.timeMs).coerceAtLeast(1L)
            builder.addStroke(GestureDescription.StrokeDescription(path, first.timeMs, duration))
        }
        return try {
            dispatchGesture(builder.build(), null, null)
        } catch (t: Throwable) {
            false
        }
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
