package com.benton.izukijs.ui.permission

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.core.net.HttpSettings
import com.benton.izukijs.core.permission.LocalNetworkPermission
import com.benton.izukijs.core.permission.SystemPermissionChecks
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.runtime.RuntimeSettings
import com.benton.izukijs.service.IzukiAccessibilityService
import com.benton.izukijs.service.ScreenCaptureService
import com.benton.izukijs.ui.common.ConfirmDialog
import com.benton.izukijs.ui.common.IzukiPageScaffold
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.PageColumn
import com.benton.izukijs.ui.common.rememberAppHaptics
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.launch

/**
 * 分组维度：读取（获取屏幕 / 控件树）、操作（注入输入 / 执行命令）、附加能力（用户级开关）。
 * 一项能力若同时属于多组，会在多个分组下各展示一张卡片，授权状态由同一份数据源驱动，自动同步。
 */
private enum class PermissionGroup(@StringRes val labelRes: Int) {
    READ(R.string.perm_group_read),
    OPERATE(R.string.perm_group_operate),
    OTHER(R.string.perm_group_other),
}

private enum class RevokeTarget(
    @StringRes val labelRes: Int,
    @StringRes val messageRes: Int,
) {
    HID(R.string.control_hid, R.string.perm_revoke_hid_message),
    ACCESSIBILITY(R.string.control_accessibility, R.string.perm_revoke_accessibility_message),
    SHIZUKU(R.string.control_shizuku, R.string.perm_revoke_shizuku_message),
    ROOT(R.string.control_root, R.string.perm_revoke_root_message),
}

/**
 * 统一的「权限与能力」授权入口：控制后端、屏幕捕获与系统授予项。
 * 顶部以分组标签切换「读取 / 操作 / 附加能力」，并展示读取与操作的整体授权状态；
 * 附加能力内再按「系统权限 / 脚本能力」分组，集中通知、悬浮窗、蓝牙等分散授权项。
 * 运行超时、保持唤醒、快捷磁贴等运行设置仍在「设置」页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(
    onBack: () -> Unit,
    onOpenHid: () -> Unit,
    onOpenAdb: () -> Unit,
) {
    val container = rememberAppContainer()
    val readyModes by container.controllerManager.effectiveReadyModes.collectAsStateWithLifecycle()
    val screenCaptureActive by container.screenCapture.active.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = LocalSnackbarController.current
    val haptics = rememberAppHaptics()

    val shizukuManager = container.shizukuManager
    val rootManager = container.rootManager
    val adbManager = container.adbManager
    val adbState by adbManager.state.collectAsStateWithLifecycle()

    val accessibilityReady = ControlMode.ACCESSIBILITY in readyModes
    val shizukuReady = ControlMode.SHIZUKU in readyModes
    val shizukuRunning = shizukuManager.isShizukuRunning()
    val rootReady = ControlMode.ROOT in readyModes
    val hidReady = ControlMode.HID in readyModes
    val adbReady = ControlMode.ADB in readyModes

    val capturePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenCaptureService.start(context, result.resultCode, data)
        }
    }

    // ---- 使用情况访问 / 通知监听 / 全部文件访问：系统授予项在返回本页时刷新 ----
    var permissionRefresh by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRefresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val usageAccessGranted = remember(permissionRefresh) { hasUsageAccess(context) }
    val notificationListenerGranted = remember(permissionRefresh) { hasNotificationListener(context) }
    val allFilesGranted = remember(permissionRefresh) { hasAllFilesAccess(context) }
    val ignoringBatteryOptimizations = remember(permissionRefresh) { isIgnoringBatteryOptimizations(context) }
    val notificationsGranted = remember(permissionRefresh) { SystemPermissionChecks.notificationsEnabled(context) }
    val overlayGranted = remember(permissionRefresh) { SystemPermissionChecks.canDrawOverlays(context) }
    val bluetoothGranted = remember(permissionRefresh) { SystemPermissionChecks.bluetoothGranted(context) }
    val localNetworkApplicable = SystemPermissionChecks.localNetworkApplicable()
    val localNetworkGranted = remember(permissionRefresh) { SystemPermissionChecks.localNetworkGranted(context) }
    val exactAlarmApplicable = SystemPermissionChecks.exactAlarmsApplicable()
    val exactAlarmGranted = remember(permissionRefresh) { SystemPermissionChecks.canScheduleExactAlarms(context) }
    val installUnknownAppsGranted = remember(permissionRefresh) {
        SystemPermissionChecks.canInstallUnknownApps(context)
    }
    val runtimeSettings by container.runtimeSettingsRepository.settings.collectAsStateWithLifecycle()
    val httpSettings by container.httpSettingsRepository.settings.collectAsStateWithLifecycle()

    val writeStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionRefresh++ }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionRefresh++
        // 用户选择“不再询问”后再次申请会静默失败，直接跳到应用通知设置。
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val activity = context as? Activity
            if (activity != null &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionRefresh++ }

    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionRefresh++ }

    fun openSystemSettings(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun saveRuntimeSettings(new: RuntimeSettings) = container.runtimeSettingsRepository.save(new)
    fun saveHttpSettings(new: HttpSettings) = container.httpSettingsRepository.save(new)

    fun openBatterySettings() {
        val intent = if (ignoringBatteryOptimizations) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            )
        }
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            openSystemSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    val pagerState = rememberPagerState(pageCount = { PermissionGroup.entries.size })
    val scope = rememberCoroutineScope()
    var infoSheet by remember { mutableStateOf<PermissionInfo?>(null) }
    var pendingRevoke by remember { mutableStateOf<RevokeTarget?>(null) }

    // 任一读取 / 操作后端就绪即视为该维度已授权。
    val readReady = screenCaptureActive || accessibilityReady || shizukuReady || adbReady || rootReady
    val operateReady = hidReady || accessibilityReady || shizukuReady || adbReady || rootReady

    val state = PermissionUiState(
        screenCaptureActive = screenCaptureActive,
        hidReady = hidReady,
        accessibilityReady = accessibilityReady,
        shizukuReady = shizukuReady,
        shizukuRunning = shizukuRunning,
        adbReady = adbReady,
        adbSupported = adbManager.isSupported(),
        adbState = adbState,
        adbHasLocalNetworkPermission = adbManager.hasLocalNetworkPermission(),
        adbHasIdentity = adbManager.hasIdentity(),
        rootReady = rootReady,
        usageAccessGranted = usageAccessGranted,
        notificationListenerGranted = notificationListenerGranted,
        allFilesGranted = allFilesGranted,
        ignoringBatteryOptimizations = ignoringBatteryOptimizations,
        notificationsGranted = notificationsGranted,
        overlayGranted = overlayGranted,
        bluetoothGranted = bluetoothGranted,
        localNetworkGranted = localNetworkGranted,
        localNetworkApplicable = localNetworkApplicable,
        exactAlarmGranted = exactAlarmGranted,
        exactAlarmApplicable = exactAlarmApplicable,
        installUnknownAppsGranted = installUnknownAppsGranted,
        runtimeSettings = runtimeSettings,
        httpSettings = httpSettings,
    )

    val actions = PermissionActions(
        showInfo = { infoSheet = it },
        startScreenCapture = {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            runCatching { capturePermissionLauncher.launch(manager.createScreenCaptureIntent()) }
        },
        stopScreenCapture = {
            // 停服务会走 onDestroy() -> ScreenCapture.stop()，这里不再重复调用，
            // 也避免在主线程做投影/ImageReader 清理。
            ScreenCaptureService.stop(context)
        },
        openHid = onOpenHid,
        revokeHid = { pendingRevoke = RevokeTarget.HID },
        openAccessibilitySettings = {
            openSystemSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        disableAccessibility = { pendingRevoke = RevokeTarget.ACCESSIBILITY },
        requestShizuku = { shizukuManager.requestPermission(SHIZUKU_REQUEST_CODE) },
        revokeShizuku = { pendingRevoke = RevokeTarget.SHIZUKU },
        openAdb = onOpenAdb,
        refreshRoot = { rootManager.refresh() },
        revokeRoot = { pendingRevoke = RevokeTarget.ROOT },
        openUsageAccessSettings = {
            openSystemSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        },
        openNotificationListenerSettings = {
            openSystemSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        },
        requestAllFiles = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                openSystemSettings(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            } else {
                writeStorageLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        },
        saveRuntimeSettings = ::saveRuntimeSettings,
        saveHttpSettings = ::saveHttpSettings,
        openBatterySettings = ::openBatterySettings,
        requestNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openSystemSettings(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
        },
        openNotificationSettings = {
            openSystemSettings(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        },
        requestOverlay = {
            openSystemSettings(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        },
        requestBluetooth = {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            bluetoothPermissionLauncher.launch(permissions)
        },
        requestLocalNetwork = {
            localNetworkPermissionLauncher.launch(LocalNetworkPermission.permission)
        },
        requestExactAlarm = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val opened = runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:${context.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.isSuccess
                if (!opened) {
                    openSystemSettings(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
            }
        },
        requestInstallUnknownApps = {
            openSystemSettings(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        },
        openAppSettings = {
            openSystemSettings(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        },
    )

    IzukiPageScaffold(
        title = { Text(stringResource(R.string.run_permissions_title)) },
        onBack = onBack,
        actions = {
            IconButton(onClick = { shizukuManager.refresh(); adbManager.refresh(); permissionRefresh++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp)) {
                    Spacer(Modifier.height(16.dp))
                    PermissionStatusHeader(readReady = readReady, operateReady = operateReady)
                    Spacer(Modifier.height(16.dp))
                    PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                        PermissionGroup.entries.forEachIndexed { index, group ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                text = { Text(stringResource(group.labelRes)) },
                            )
                        }
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                PageColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
                    when (PermissionGroup.entries[page]) {
                        PermissionGroup.READ -> {
                            ScreenCaptureCard(state, actions)
                            Spacer(Modifier.height(12.dp))
                            AccessibilityCard(state, actions)
                            Spacer(Modifier.height(12.dp))
                            ShizukuCard(state, actions)
                            Spacer(Modifier.height(12.dp))
                            AdbCard(state, actions)
                            Spacer(Modifier.height(12.dp))
                            RootCard(state, actions)
                            Spacer(Modifier.height(12.dp))
                            NotificationListenerCard(state, actions)
                            Spacer(Modifier.height(12.dp))
                            UsageAccessCard(state, actions)
                            Spacer(Modifier.height(12.dp))
                            AllFilesCard(state, actions)
                        }

                        PermissionGroup.OPERATE -> {
                            HidCard(state, actions)
                            Spacer(Modifier.height(12.dp))
                            AccessibilityCard(state, actions)
                            Spacer(Modifier.height(12.dp))
                            ShizukuCard(state, actions)
                            Spacer(Modifier.height(12.dp))
                            AdbCard(state, actions)
                            Spacer(Modifier.height(12.dp))
                            RootCard(state, actions)
                        }

                        PermissionGroup.OTHER -> {
                            PermissionSectionHeader(R.string.perm_section_system)
                            Spacer(Modifier.height(8.dp))
                            NotificationsCard(state, actions)
                            Spacer(Modifier.height(12.dp))
                            OverlayCard(state, actions)
                            Spacer(Modifier.height(12.dp))
                            KeepAliveCard(state, actions)
                            Spacer(Modifier.height(12.dp))
                            BluetoothCard(state, actions)
                            if (state.localNetworkApplicable) {
                                Spacer(Modifier.height(12.dp))
                                LocalNetworkCard(state, actions)
                            }
                            if (state.exactAlarmApplicable) {
                                Spacer(Modifier.height(12.dp))
                                ExactAlarmCard(state, actions)
                            }
                            Spacer(Modifier.height(12.dp))
                            InstallUnknownAppsCard(state, actions)
                            Spacer(Modifier.height(20.dp))
                            PermissionSectionHeader(R.string.perm_section_script)
                            Spacer(Modifier.height(8.dp))
                            AppListCard(state, actions)
                            Spacer(Modifier.height(12.dp))
                            HttpCard(state, actions)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    infoSheet?.let { info ->
        PermissionInfoSheet(info = info, onDismiss = { infoSheet = null })
    }

    pendingRevoke?.let { target ->
        val label = stringResource(target.labelRes)
        ConfirmDialog(
            title = stringResource(R.string.perm_revoke_confirm_title, label),
            message = stringResource(target.messageRes),
            confirmLabel = stringResource(R.string.perm_action_revoke),
            destructive = true,
            onConfirm = {
                pendingRevoke = null
                haptics.reject()
                when (target) {
                    RevokeTarget.HID -> container.hidManager.revoke()
                    RevokeTarget.ACCESSIBILITY -> IzukiAccessibilityService.instance?.disable()
                    RevokeTarget.SHIZUKU -> shizukuManager.revoke()
                    RevokeTarget.ROOT -> rootManager.revoke()
                }
                scope.launch {
                    snackbar.show(resources.getString(R.string.perm_revoke_done, label))
                }
            },
            onDismiss = { pendingRevoke = null },
        )
    }
}
