package com.benton.izukijs.runtime.api

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.webkit.JavascriptInterface
import com.benton.izukijs.virtualscreen.ScreenTarget
import com.benton.izukijs.virtualscreen.VirtualScreenManager
import com.benton.izukijs.virtualscreen.VirtualScreenResolution

/**
 * 设备信息 API。挂在全局 `device` 命名空间。
 *
 * 虚拟屏激活且当前目标为虚拟屏时，宽高返回虚拟屏分辨率，保证脚本里按
 * `device.width()/height()` 计算的坐标与截图、OCR、点击使用同一坐标系。
 * 其它情况下 [width] / [height] 返回应用窗口度量，[screenWidth] / [screenHeight]
 * 返回物理屏幕的真实分辨率。
 */
class DeviceApi(
    private val context: Context,
    private val virtualScreen: VirtualScreenManager? = null,
) {

    @JavascriptInterface
    fun width(): Int = virtualResolution()?.width ?: context.resources.displayMetrics.widthPixels

    @JavascriptInterface
    fun height(): Int = virtualResolution()?.height ?: context.resources.displayMetrics.heightPixels

    @JavascriptInterface
    fun screenWidth(): Int = virtualResolution()?.width ?: realMetrics().widthPixels

    @JavascriptInterface
    fun screenHeight(): Int = virtualResolution()?.height ?: realMetrics().heightPixels

    private fun virtualResolution(): VirtualScreenResolution? =
        virtualScreen
            ?.takeIf { it.isActive() && it.resolvedTarget() == ScreenTarget.VIRTUAL }
            ?.size()

    private fun realMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val display = runCatching {
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull()
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        } else {
            metrics.setTo(context.resources.displayMetrics)
        }
        return metrics
    }

    @JavascriptInterface
    fun model(): String = Build.MODEL ?: "unknown"

    @JavascriptInterface
    fun brand(): String = Build.BRAND ?: "unknown"

    @JavascriptInterface
    fun androidVersion(): Int = Build.VERSION.SDK_INT

    @JavascriptInterface
    fun batteryLevel(): Int {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }
}
