package com.benton.izukijs.runtime.api

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.webkit.JavascriptInterface

/**
 * 设备信息 API。挂在全局 `device` 命名空间。
 *
 * [width] / [height] 返回应用窗口度量（可能不含系统栏），[screenWidth] / [screenHeight]
 * 返回物理屏幕的真实分辨率——后者与截图、无障碍控件坐标、注入点击使用的是同一坐标系。
 */
class DeviceApi(private val context: Context) {

    @JavascriptInterface
    fun width(): Int = context.resources.displayMetrics.widthPixels

    @JavascriptInterface
    fun height(): Int = context.resources.displayMetrics.heightPixels

    @JavascriptInterface
    fun screenWidth(): Int = realMetrics().widthPixels

    @JavascriptInterface
    fun screenHeight(): Int = realMetrics().heightPixels

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
