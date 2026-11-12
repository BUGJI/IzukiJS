package com.benton.izukijs.ui.permission

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.core.net.HttpSettings
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.runtime.RuntimeSettings
import com.benton.izukijs.service.IzukiAccessibilityService
import com.benton.izukijs.service.ScreenCaptureService
import com.benton.izukijs.ui.common.IzukiPageScaffold
import com.benton.izukijs.ui.common.PageColumn
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

/**
 * 统一的「权限与能力」授权入口：控制后端、屏幕捕获与系统授予项。
 * 顶部以分组标签切换「读取 / 操作 / 附加能力」，并展示读取与操作的整体授权状态；
 * 运行超时、保持唤醒、悬浮窗、快捷磁贴等运行设置已统一移至「设置」页。
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
    val runtimeSettings by container.runtimeSettingsRepository.settings.collectAsStateWithLifecycle()
    val httpSettings by container.httpSettingsRepository.settings.collectAsStateWithLifecycle()

    val writeStorageLauncher = rememberLauncherForActivityResult(
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
        revokeHid = { container.hidManager.revoke() },
        openAccessibilitySettings = {
            openSystemSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        disableAccessibility = { IzukiAccessibilityService.instance?.disable() },
        requestShizuku = { shizukuManager.requestPermission(SHIZUKU_REQUEST_CODE) },
        revokeShizuku = { shizukuManager.revoke() },
        openAdb = onOpenAdb,
        refreshRoot = { rootManager.refresh() },
        revokeRoot = { rootManager.revoke() },
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
                            AppListCard(state, actions)
                            Spacer(Modifier.height(12.dp))
                            HttpCard(state, actions)
                            Spacer(Modifier.height(12.dp))
                            KeepAliveCard(state, actions)
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
}
