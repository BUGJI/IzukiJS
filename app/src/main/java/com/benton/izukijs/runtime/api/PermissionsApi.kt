package com.benton.izukijs.runtime.api

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationManagerCompat
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
    private val appListEnabled: () -> Boolean = { true },
    private val httpEnabled: () -> Boolean = { false },
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

    /** 使用情况访问（PACKAGE_USAGE_STATS）。 */
    @JavascriptInterface
    fun usageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
        }.getOrDefault(AppOpsManager.MODE_DEFAULT)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 通知使用权（通知监听服务已开启）。 */
    @JavascriptInterface
    fun notificationListener(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** 全部文件访问（MANAGE_EXTERNAL_STORAGE，或旧版外部存储写权限）。 */
    @JavascriptInterface
    fun manageStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            isGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

    /** 应用列表（QUERY_ALL_PACKAGES + 应用内开关）。 */
    @JavascriptInterface
    fun appList(): Boolean = appListEnabled()

    /** 脚本网络（HTTP）是否启用。 */
    @JavascriptInterface
    fun http(): Boolean = httpEnabled()

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
        "usageAccess" to usageAccess(),
        "notificationListener" to notificationListener(),
        "manageStorage" to manageStorage(),
        "appList" to appList(),
        "http" to http(),
    )

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
