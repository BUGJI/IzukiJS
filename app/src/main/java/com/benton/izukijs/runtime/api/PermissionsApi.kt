package com.benton.izukijs.runtime.api

import android.content.Context
import android.webkit.JavascriptInterface
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.core.permission.SystemPermissionChecks
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.model.RunMode
import com.benton.izukijs.service.ScreenCapture
import com.benton.izukijs.virtualscreen.ScreenTarget
import com.benton.izukijs.virtualscreen.VirtualScreenManager

/**
 * 权限查询 API。挂在全局 `permissions` 命名空间，供脚本判断用户已授予哪些能力。
 */
class PermissionsApi(
    private val context: Context,
    private val controllers: ControllerManager,
    private val screenCapture: ScreenCapture,
    private val appListEnabled: () -> Boolean = { true },
    private val httpEnabled: () -> Boolean = { false },
    private val virtualScreen: VirtualScreenManager? = null,
    private val runModeProvider: () -> RunMode = { RunMode.FOREGROUND },
) {

    @JavascriptInterface
    fun accessibility(): Boolean = ControlMode.ACCESSIBILITY in controllers.readyModes.value

    @JavascriptInterface
    fun shizuku(): Boolean = ControlMode.SHIZUKU in controllers.readyModes.value

    /** 无线调试（ADB over Wi-Fi）后端是否就绪。 */
    @JavascriptInterface
    fun adb(): Boolean = ControlMode.ADB in controllers.readyModes.value

    @JavascriptInterface
    fun root(): Boolean = ControlMode.ROOT in controllers.readyModes.value

    @JavascriptInterface
    fun hid(): Boolean = ControlMode.HID in controllers.readyModes.value

    @JavascriptInterface
    fun overlay(): Boolean = SystemPermissionChecks.canDrawOverlays(context)

    @JavascriptInterface
    fun screenCapture(): Boolean = screenCapture.isActive

    @JavascriptInterface
    fun notifications(): Boolean = SystemPermissionChecks.notificationsEnabled(context)

    /** 修改系统设置（WRITE_SETTINGS）；`device.setBrightness` 等需要。 */
    @JavascriptInterface
    fun writeSettings(): Boolean = SystemPermissionChecks.canWriteSettings(context)

    @JavascriptInterface
    fun bluetooth(): Boolean = SystemPermissionChecks.bluetoothGranted(context)

    /** 使用情况访问（PACKAGE_USAGE_STATS）。 */
    @JavascriptInterface
    fun usageAccess(): Boolean = SystemPermissionChecks.hasUsageAccess(context)

    /** 通知使用权（通知监听服务已开启）。 */
    @JavascriptInterface
    fun notificationListener(): Boolean = SystemPermissionChecks.hasNotificationListener(context)

    /** 全部文件访问（MANAGE_EXTERNAL_STORAGE，或旧版外部存储写权限）。 */
    @JavascriptInterface
    fun manageStorage(): Boolean = SystemPermissionChecks.hasAllFilesAccess(context)

    /** 本地网络（Android 17 / API 37 起需要）。 */
    @JavascriptInterface
    fun localNetwork(): Boolean = SystemPermissionChecks.localNetworkGranted(context)

    /** 精确闹钟（Android 12 / API 31 起需要）。 */
    @JavascriptInterface
    fun exactAlarm(): Boolean = SystemPermissionChecks.canScheduleExactAlarms(context)

    /** 安装未知来源应用（应用内更新）。 */
    @JavascriptInterface
    fun installUnknownApps(): Boolean = SystemPermissionChecks.canInstallUnknownApps(context)

    /** 应用列表（QUERY_ALL_PACKAGES + 应用内开关）。 */
    @JavascriptInterface
    fun appList(): Boolean = appListEnabled()

    /** 脚本网络（HTTP）是否启用。 */
    @JavascriptInterface
    fun http(): Boolean = httpEnabled()

    /** 后台虚拟屏的 Shizuku 特权后端是否可用。 */
    @JavascriptInterface
    fun virtualScreen(): Boolean = virtualScreen?.available() == true

    /** 本次脚本实际生效的运行模式：foreground / background。 */
    @JavascriptInterface
    fun runMode(): String =
        if (virtualScreen?.isActive() == true) RunMode.BACKGROUND.name.lowercase()
        else runModeProvider().name.lowercase()

    /** 已授予的能力，逗号分隔（如 "accessibility,overlay,screenCapture"）。 */
    @JavascriptInterface
    fun granted(): String = statusMap().filterValues { it }.keys.joinToString(",")

    /** 全部能力的状态，逗号分隔的 key=value。 */
    @JavascriptInterface
    fun all(): String = statusMap().entries.joinToString(",") { "${it.key}=${it.value}" }

    /** 查询单个能力，name 同 [granted] 中的 key。 */
    @JavascriptInterface
    fun has(name: String): Boolean = statusMap()[name.trim().lowercase()] ?: false

    /** 是否有任意可用于注入输入的控制后端；目标为虚拟屏时只统计支持 display 定向的后端。 */
    @JavascriptInterface
    fun canControl(): Boolean =
        if (usesVirtualDisplay()) {
            controllers.controllerFor(Capability.GESTURE) != null
        } else {
            ControlMode.ACCESSIBILITY in controllers.readyModes.value ||
                ControlMode.SHIZUKU in controllers.readyModes.value ||
                ControlMode.ADB in controllers.readyModes.value ||
                ControlMode.ROOT in controllers.readyModes.value ||
                ControlMode.HID in controllers.readyModes.value
        }

    /** 是否有截图来源（无障碍/Shizuku/无线调试/Root 或持续录屏；目标为虚拟屏时为虚拟屏帧源）。 */
    @JavascriptInterface
    fun canScreenshot(): Boolean =
        if (usesVirtualDisplay()) {
            virtualScreen?.canCapture() == true
        } else {
            screenCapture.isActive || canControl()
        }

    private fun usesVirtualDisplay(): Boolean =
        virtualScreen?.let {
            it.isActive() && it.resolvedTarget() == ScreenTarget.VIRTUAL
        } == true

    private fun statusMap(): Map<String, Boolean> = linkedMapOf(
        "accessibility" to accessibility(),
        "shizuku" to shizuku(),
        "adb" to adb(),
        "root" to root(),
        "hid" to hid(),
        "overlay" to overlay(),
        "screenCapture" to screenCapture(),
        "notifications" to notifications(),
        "bluetooth" to bluetooth(),
        "usageAccess" to usageAccess(),
        "notificationListener" to notificationListener(),
        "manageStorage" to manageStorage(),
        "writeSettings" to writeSettings(),
        "localNetwork" to localNetwork(),
        "exactAlarm" to exactAlarm(),
        "installUnknownApps" to installUnknownApps(),
        "appList" to appList(),
        "http" to http(),
        "virtualScreen" to virtualScreen(),
    )
}
