package com.benton.izukijs.ui.permission

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** 使用情况访问（PACKAGE_USAGE_STATS）是否已授予。 */
@Suppress("DEPRECATION")
internal fun hasUsageAccess(context: Context): Boolean {
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

/** 通知使用权（通知监听服务）是否已授予。 */
internal fun hasNotificationListener(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

/** 是否已忽略电池优化（加入白名单），用于后台保活。 */
internal fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return manager.isIgnoringBatteryOptimizations(context.packageName)
}

/** 全部文件访问是否已授予。 */
internal fun hasAllFilesAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    } else {
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
