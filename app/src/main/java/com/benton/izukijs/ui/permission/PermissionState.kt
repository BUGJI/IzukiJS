package com.benton.izukijs.ui.permission

import com.benton.izukijs.controller.adb.AdbConnectionManager
import com.benton.izukijs.core.net.HttpSettings
import com.benton.izukijs.runtime.RuntimeSettings

/** 「权限与能力」页面中所有卡片共享的只读状态。 */
internal data class PermissionUiState(
    val screenCaptureActive: Boolean = false,
    val hidReady: Boolean = false,
    val accessibilityReady: Boolean = false,
    val shizukuReady: Boolean = false,
    val shizukuRunning: Boolean = false,
    val adbReady: Boolean = false,
    val adbSupported: Boolean = false,
    val adbState: AdbConnectionManager.State = AdbConnectionManager.State.Disconnected,
    val adbHasLocalNetworkPermission: Boolean = false,
    val adbHasIdentity: Boolean = false,
    val rootReady: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val notificationListenerGranted: Boolean = false,
    val allFilesGranted: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = false,
    val runtimeSettings: RuntimeSettings = RuntimeSettings(),
    val httpSettings: HttpSettings = HttpSettings(),
)

/** 卡片可触发的动作。页面负责把系统 launcher、服务与仓库操作收拢到这里。 */
internal data class PermissionActions(
    val showInfo: (PermissionInfo) -> Unit,
    val startScreenCapture: () -> Unit,
    val stopScreenCapture: () -> Unit,
    val openHid: () -> Unit,
    val revokeHid: () -> Unit,
    val openAccessibilitySettings: () -> Unit,
    val disableAccessibility: () -> Unit,
    val requestShizuku: () -> Unit,
    val revokeShizuku: () -> Unit,
    val openAdb: () -> Unit,
    val refreshRoot: () -> Unit,
    val revokeRoot: () -> Unit,
    val openUsageAccessSettings: () -> Unit,
    val openNotificationListenerSettings: () -> Unit,
    val requestAllFiles: () -> Unit,
    val saveRuntimeSettings: (RuntimeSettings) -> Unit,
    val saveHttpSettings: (HttpSettings) -> Unit,
    val openBatterySettings: () -> Unit,
)
