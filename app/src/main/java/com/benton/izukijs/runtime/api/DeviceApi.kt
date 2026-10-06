package com.benton.izukijs.runtime.api

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.webkit.JavascriptInterface

/**
 * 设备信息 API。挂在全局 `device` 命名空间。
 */
class DeviceApi(private val context: Context) {

    @JavascriptInterface
    fun width(): Int = context.resources.displayMetrics.widthPixels

    @JavascriptInterface
    fun height(): Int = context.resources.displayMetrics.heightPixels

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
