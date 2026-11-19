package com.benton.izukijs.core.permission

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 系统授予项的实时读取入口。
 *
 * 权限与能力页、脚本 `permissions.*`、设置页与 HID 扫描都从这里取状态，避免同一判断
 * 在多处实现后逐渐不一致。这里只做只读查询，不申请权限，也不缓存结果。
 */
object SystemPermissionChecks {

    /** 通知总开关：既覆盖 Android 13+ 运行时权限，也覆盖更低版本的应用级通知开关。 */
    fun notificationsEnabled(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
    }

    /** 「显示在其他应用上层」特殊权限。 */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** 「修改系统设置」特殊权限；脚本调整亮度等场景使用。 */
    fun canWriteSettings(context: Context): Boolean =
        runCatching { Settings.System.canWrite(context) }.getOrDefault(false)

    /** 蓝牙扫描 / 连接权限；Android 12 以下扫描依赖定位权限。 */
    fun bluetoothGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isGranted(context, Manifest.permission.BLUETOOTH_SCAN) &&
                isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** 使用情况访问（PACKAGE_USAGE_STATS）。 */
    @Suppress("DEPRECATION")
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }
        }.getOrDefault(AppOpsManager.MODE_DEFAULT)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 通知使用权（通知监听服务已开启）。 */
    fun hasNotificationListener(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** 是否已忽略电池优化（加入白名单），用于后台保活。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return manager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 全部文件访问是否已授予。 */
    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            isGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

    /** Android 17（API 37）起才需要本地网络权限，更低版本视为已满足。 */
    fun localNetworkApplicable(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        LocalNetworkPermission.isRequired(sdkInt)

    fun localNetworkGranted(context: Context): Boolean = LocalNetworkPermission.isGranted(context)

    /** Android 12（API 31）起精确闹钟需要单独授权，更低版本视为已满足。 */
    fun exactAlarmsApplicable(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.S

    fun canScheduleExactAlarms(context: Context): Boolean {
        // 直接判断 SDK 版本，lint 才能识别 API 31 调用；不要只依赖自定义包装函数。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        return runCatching { manager?.canScheduleExactAlarms() == true }.getOrDefault(false)
    }

    /** 安装未知来源应用（应用内更新）。minSdk 28，因此始终走可查询分支。 */
    fun canInstallUnknownApps(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
