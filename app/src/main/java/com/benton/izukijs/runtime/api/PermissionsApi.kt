package com.benton.izukijs.runtime.api

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.service.ScreenCapture

/**
 * 权限查询 API。挂在全局 `permissions` 命名空间，供脚本判断用户已授予哪些能力。
 */
class PermissionsApi(
    private val context: Context,
    private val controllers: ControllerManager,
    private val screenCapture: ScreenCapture,
) {

    @JavascriptInterface
    fun accessibility(): Boolean = ControlMode.ACCESSIBILITY in controllers.readyModes.value

    @JavascriptInterface
    fun shizuku(): Boolean = ControlMode.SHIZUKU in controllers.readyModes.value

    @JavascriptInterface
    fun root(): Boolean = ControlMode.ROOT in controllers.readyModes.value

    @JavascriptInterface
    fun hid(): Boolean = ControlMode.HID in controllers.readyModes.value

    @JavascriptInterface
    fun overlay(): Boolean = Settings.canDrawOverlays(context)

    @JavascriptInterface
    fun screenCapture(): Boolean = screenCapture.isActive

    @JavascriptInterface
    fun notifications(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

    @JavascriptInterface
    fun bluetooth(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isGranted(Manifest.permission.BLUETOOTH_CONNECT) && isGranted(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** 已授予的能力，逗号分隔（如 "accessibility,overlay,screenCapture"）。 */
    @JavascriptInterface
    fun granted(): String = statusMap().filterValues { it }.keys.joinToString(",")

    /** 全部能力的状态，逗号分隔的 key=value。 */
    @JavascriptInterface
    fun all(): String = statusMap().entries.joinToString(",") { "${it.key}=${it.value}" }

    /** 查询单个能力，name 同 [granted] 中的 key。 */
    @JavascriptInterface
    fun has(name: String): Boolean = statusMap()[name.trim().lowercase()] ?: false

    /** 是否有任意可用于注入输入的控制后端。 */
    @JavascriptInterface
    fun canControl(): Boolean =
        ControlMode.ACCESSIBILITY in controllers.readyModes.value ||
            ControlMode.SHIZUKU in controllers.readyModes.value ||
            ControlMode.ROOT in controllers.readyModes.value ||
            ControlMode.HID in controllers.readyModes.value

    /** 是否有截图来源（无障碍/Shizuku/Root 或持续录屏）。 */
    @JavascriptInterface
    fun canScreenshot(): Boolean = screenCapture.isActive || canControl()

    private fun statusMap(): Map<String, Boolean> = linkedMapOf(
        "accessibility" to accessibility(),
        "shizuku" to shizuku(),
        "root" to root(),
        "hid" to hid(),
        "overlay" to overlay(),
        "screenCapture" to screenCapture(),
        "notifications" to notifications(),
        "bluetooth" to bluetooth(),
    )

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
