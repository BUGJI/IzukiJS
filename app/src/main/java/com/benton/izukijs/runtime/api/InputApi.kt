package com.benton.izukijs.runtime.api

import android.webkit.JavascriptInterface
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.model.Capability

/**
 * 输入 API：click / swipe / press / back / home / recents / input。
 * 通过能力选择背后的控制模式，脚本层无需关心底层是无障碍、Shizuku 还是 HID。
 */
class InputApi(private val controllers: ControllerManager) {

    private fun gesture() = controllers.controllerFor(Capability.GESTURE)

    @JavascriptInterface
    fun click(x: Double, y: Double): Boolean =
        gesture()?.click(x.toFloat(), y.toFloat()) ?: false

    @JavascriptInterface
    fun longClick(x: Double, y: Double, durationMillis: Double): Boolean =
        gesture()?.longClick(x.toFloat(), y.toFloat(), durationMillis.toLong()) ?: false

    @JavascriptInterface
    fun swipe(
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        durationMillis: Double,
    ): Boolean = gesture()?.swipe(
        x1.toFloat(),
        y1.toFloat(),
        x2.toFloat(),
        y2.toFloat(),
        durationMillis.toLong(),
    ) ?: false

    @JavascriptInterface
    fun press(keyCode: Int): Boolean =
        controllers.controllerFor(Capability.KEY)?.pressKey(keyCode) ?: false

    @JavascriptInterface
    fun input(text: String): Boolean =
        controllers.controllerFor(Capability.TEXT)?.inputText(text) ?: false

    @JavascriptInterface
    fun back(): Boolean = globalAction(GLOBAL_BACK)

    @JavascriptInterface
    fun home(): Boolean = globalAction(GLOBAL_HOME)

    @JavascriptInterface
    fun recents(): Boolean = globalAction(GLOBAL_RECENTS)

    private fun globalAction(action: Int): Boolean =
        controllers.controllerFor(Capability.GLOBAL_ACTION)?.globalAction(action) ?: false

    private companion object {
        const val GLOBAL_BACK = 1 // AccessibilityService.GLOBAL_ACTION_BACK
        const val GLOBAL_HOME = 2 // AccessibilityService.GLOBAL_ACTION_HOME
        const val GLOBAL_RECENTS = 3 // AccessibilityService.GLOBAL_ACTION_RECENTS
    }
}
