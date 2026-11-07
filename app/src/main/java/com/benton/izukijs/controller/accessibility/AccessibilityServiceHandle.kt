package com.benton.izukijs.controller.accessibility

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import com.benton.izukijs.controller.GestureStroke

/**
 * 无障碍服务向控制层暴露的最小能力接口。
 *
 * `AccessibilityController` 只需要这些操作，依赖接口即可避免 controller 反向依赖
 * service 的具体实现（从而打断 controller ↔ service 的包循环）。
 * 由 `IzukiAccessibilityService` 实现。
 */
interface AccessibilityServiceHandle {

    fun tapAt(x: Float, y: Float, durationMs: Long): Boolean

    fun swipeFromTo(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean

    fun performStrokes(strokes: List<GestureStroke>): Boolean

    fun inputText(text: String): Boolean

    /** 由 `AccessibilityService` 继承实现。 */
    fun performGlobalAction(action: Int): Boolean

    fun screenshotBitmap(): Bitmap?

    fun rootNode(): AccessibilityNodeInfo?
}
