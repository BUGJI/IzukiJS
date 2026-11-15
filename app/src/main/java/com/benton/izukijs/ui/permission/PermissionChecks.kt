package com.benton.izukijs.ui.permission

import android.content.Context
import com.benton.izukijs.core.permission.SystemPermissionChecks

/**
 * 页面内部的薄封装，实际判断统一收敛到 [SystemPermissionChecks]。
 * 保留这些函数名是为了让卡片调用点保持简短。
 */
internal fun hasUsageAccess(context: Context): Boolean =
    SystemPermissionChecks.hasUsageAccess(context)

internal fun hasNotificationListener(context: Context): Boolean =
    SystemPermissionChecks.hasNotificationListener(context)

internal fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    SystemPermissionChecks.isIgnoringBatteryOptimizations(context)

internal fun hasAllFilesAccess(context: Context): Boolean =
    SystemPermissionChecks.hasAllFilesAccess(context)
