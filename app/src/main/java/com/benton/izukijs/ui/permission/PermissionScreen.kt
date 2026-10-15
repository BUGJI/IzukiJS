package com.benton.izukijs.ui.permission

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.runtime.RuntimeSettings
import com.benton.izukijs.service.DebugOverlayService
import com.benton.izukijs.service.FloatingWindowService
import com.benton.izukijs.service.IzukiAccessibilityService
import com.benton.izukijs.service.ScreenCaptureService
import com.benton.izukijs.ui.common.PageColumn
import com.benton.izukijs.ui.common.SwitchRow
import com.benton.izukijs.ui.common.stableTopAppBarColors
import com.benton.izukijs.ui.rememberAppContainer
import com.benton.izukijs.ui.theme.LocalIzukiExtraColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 分组维度：读取（获取屏幕 / 控件树）、操作（注入输入 / 执行命令）、其他。
 * 一项能力若同时属于多组，会在多个分组下各展示一张卡片，授权状态由同一份数据源驱动，自动同步。
 */
private enum class PermissionGroup(@StringRes val labelRes: Int) {
    READ(R.string.perm_group_read),
    OPERATE(R.string.perm_group_operate),
    OTHER(R.string.perm_group_other),
}

/**
 * 统一的「权限与能力」授权入口：控制后端、屏幕捕获与悬浮窗，全部是需要用户显式授权的项。
 * 顶部以分组标签切换「读取 / 操作 / 其他」，并展示读取与操作的整体授权状态；
 * 每张卡片只保留标题、状态、说明入口（?）与操作按钮，详细介绍在底部弹层中展开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(onBack: () -> Unit, onOpenHid: () -> Unit) {
    val container = rememberAppContainer()
    val readyModes by container.controllerManager.readyModes.collectAsStateWithLifecycle()
    val screenCaptureActive by container.screenCapture.active.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val shizukuManager = container.shizukuManager
    val rootManager = container.rootManager

    val accessibilityReady = ControlMode.ACCESSIBILITY in readyModes
    val shizukuReady = ControlMode.SHIZUKU in readyModes
    val shizukuRunning = shizukuManager.isShizukuRunning()
    val rootReady = ControlMode.ROOT in readyModes
    val hidReady = ControlMode.HID in readyModes

    val floatingEnabled by FloatingWindowService.active.collectAsStateWithLifecycle()
    val debugOverlayEnabled by DebugOverlayService.active.collectAsStateWithLifecycle()
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    var pendingGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(context)) {
            canDrawOverlays = true
            val action = pendingGrant
            pendingGrant = null
            action?.invoke()
        }
    }

    fun withOverlay(onGranted: () -> Unit) {
        if (Settings.canDrawOverlays(context)) {
            onGranted()
            return
        }
        pendingGrant = onGranted
        runCatching {
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

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
    val runtimeSettings by container.runtimeSettingsRepository.settings.collectAsStateWithLifecycle()

    val writeStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionRefresh++ }

    fun openSystemSettings(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun saveRuntimeSettings(new: RuntimeSettings) = container.runtimeSettingsRepository.save(new)

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val pagerState = rememberPagerState(pageCount = { PermissionGroup.entries.size })
    val scope = rememberCoroutineScope()
    var infoSheet by remember { mutableStateOf<PermissionInfo?>(null) }

    // 任一读取 / 操作后端就绪即视为该维度已授权。
    val readReady = screenCaptureActive || accessibilityReady || shizukuReady || rootReady
    val operateReady = hidReady || accessibilityReady || shizukuReady || rootReady

    // ---- 卡片定义（同时被多个分组复用的项只声明一次） ----

    @Composable
    fun ScreenCaptureCard() {
        val info = PermissionInfo(
            title = stringResource(R.string.perm_screen_capture),
            description = stringResource(R.string.perm_capture_desc),
            dimensions = listOf(
                Dimension(stringResource(R.string.dim_accuracy), stringResource(R.string.rating_high), Rating.GOOD),
                Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_low), Rating.GOOD),
                Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
            ),
            tip = stringResource(R.string.perm_capture_tip),
        )
        PermissionCard(
            info = info,
            status = stringResource(
                if (screenCaptureActive) R.string.perm_status_recording else R.string.perm_status_off,
            ),
            ready = screenCaptureActive,
            onShowInfo = { infoSheet = info },
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            val manager = context.getSystemService(MediaProjectionManager::class.java)
                            runCatching {
                                capturePermissionLauncher.launch(manager.createScreenCaptureIntent())
                            }
                        },
                        enabled = !screenCaptureActive,
                    ) {
                        Text(
                            stringResource(
                                if (screenCaptureActive) R.string.perm_status_recording else R.string.perm_action_authorize_start,
                            ),
                        )
                    }
                    if (screenCaptureActive) {
                        OutlinedButton(onClick = {
                            ScreenCaptureService.stop(context)
                            container.screenCapture.stop()
                        }) { Text(stringResource(R.string.perm_action_stop_recording)) }
                    }
                }
            },
        )
    }

    @Composable
    fun HidCard() {
        val info = PermissionInfo(
            title = stringResource(R.string.control_hid),
            description = stringResource(R.string.perm_hid_desc),
            dimensions = listOf(
                Dimension(stringResource(R.string.dim_accuracy), stringResource(R.string.rating_high), Rating.GOOD),
                Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_very_low), Rating.GOOD),
                Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_medium), Rating.WARN),
            ),
            pros = listOf(
                stringResource(R.string.perm_hid_pro_1),
                stringResource(R.string.perm_hid_pro_2),
            ),
            cons = listOf(
                stringResource(R.string.perm_hid_con_1),
                stringResource(R.string.perm_hid_con_2),
            ),
        )
        PermissionCard(
            info = info,
            status = stringResource(if (hidReady) R.string.common_ready else R.string.common_not_ready),
            ready = hidReady,
            onShowInfo = { infoSheet = info },
            action = {
                CapabilityActions(
                    actionLabel = stringResource(if (hidReady) R.string.perm_action_manage else R.string.perm_action_connect),
                    onAction = onOpenHid,
                    onRevoke = if (hidReady) {
                        ({ container.hidManager.revoke() })
                    } else {
                        null
                    },
                )
            },
        )
    }

    @Composable
    fun AccessibilityCard() {
        val info = PermissionInfo(
            title = stringResource(R.string.control_accessibility),
            description = stringResource(R.string.perm_accessibility_desc),
            dimensions = listOf(
                Dimension(stringResource(R.string.dim_accuracy), stringResource(R.string.rating_high), Rating.GOOD),
                Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_medium), Rating.WARN),
                Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
            ),
            pros = listOf(
                stringResource(R.string.perm_accessibility_pro_1),
                stringResource(R.string.perm_accessibility_pro_2),
            ),
            cons = listOf(
                stringResource(R.string.perm_accessibility_con_1),
                stringResource(R.string.perm_accessibility_con_2),
            ),
        )
        PermissionCard(
            info = info,
            status = stringResource(if (accessibilityReady) R.string.common_ready else R.string.common_not_ready),
            ready = accessibilityReady,
            onShowInfo = { infoSheet = info },
            action = {
                CapabilityActions(
                    actionLabel = stringResource(
                        if (accessibilityReady) R.string.perm_status_on else R.string.perm_action_open,
                    ),
                    onAction = if (accessibilityReady) {
                        null
                    } else {
                        {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                    },
                    onRevoke = if (accessibilityReady) {
                        ({ IzukiAccessibilityService.instance?.disable() })
                    } else {
                        null
                    },
                )
            },
        )
    }

    @Composable
    fun ShizukuCard() {
        val info = PermissionInfo(
            title = stringResource(R.string.control_shizuku),
            description = stringResource(R.string.perm_shizuku_desc),
            dimensions = listOf(
                Dimension(stringResource(R.string.dim_accuracy), stringResource(R.string.rating_high), Rating.GOOD),
                Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_medium), Rating.WARN),
                Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_complex), Rating.BAD),
            ),
            pros = listOf(
                stringResource(R.string.perm_shizuku_pro_1),
                stringResource(R.string.perm_shizuku_pro_2),
            ),
            cons = listOf(
                stringResource(R.string.perm_shizuku_con_1),
                stringResource(R.string.perm_shizuku_con_2),
            ),
        )
        PermissionCard(
            info = info,
            status = stringResource(
                when {
                    shizukuReady -> R.string.common_ready
                    !shizukuRunning -> R.string.perm_status_service_stopped
                    else -> R.string.common_not_ready
                },
            ),
            ready = shizukuReady,
            onShowInfo = { infoSheet = info },
            action = {
                CapabilityActions(
                    actionLabel = stringResource(
                        when {
                            shizukuReady -> R.string.perm_status_authorized
                            !shizukuRunning -> R.string.perm_action_shizuku_not_running
                            else -> R.string.perm_action_authorize
                        },
                    ),
                    onAction = if (shizukuReady || !shizukuRunning) {
                        null
                    } else {
                        { shizukuManager.requestPermission(SHIZUKU_REQUEST_CODE) }
                    },
                    onRevoke = if (shizukuReady) ({ shizukuManager.revoke() }) else null,
                )
            },
        )
    }

    @Composable
    fun RootCard() {
        val info = PermissionInfo(
            title = stringResource(R.string.control_root),
            description = stringResource(R.string.perm_root_desc),
            dimensions = listOf(
                Dimension(stringResource(R.string.dim_accuracy), stringResource(R.string.rating_high), Rating.GOOD),
                Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_high), Rating.BAD),
                Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_complex), Rating.BAD),
            ),
            pros = listOf(
                stringResource(R.string.perm_root_pro_1),
                stringResource(R.string.perm_root_pro_2),
            ),
            cons = listOf(
                stringResource(R.string.perm_root_con_1),
                stringResource(R.string.perm_root_con_2),
            ),
        )
        PermissionCard(
            info = info,
            status = stringResource(if (rootReady) R.string.common_ready else R.string.common_not_ready),
            ready = rootReady,
            onShowInfo = { infoSheet = info },
            action = {
                CapabilityActions(
                    actionLabel = stringResource(
                        if (rootReady) R.string.common_ready else R.string.perm_action_detect_root,
                    ),
                    onAction = if (rootReady) null else ({ rootManager.refresh() }),
                    onRevoke = if (rootReady) ({ rootManager.revoke() }) else null,
                )
            },
        )
    }

    @Composable
    fun FloatingCard() {
        val info = PermissionInfo(
            title = stringResource(R.string.perm_floating_title),
            description = stringResource(R.string.perm_floating_desc),
            dimensions = listOf(
                Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_low), Rating.GOOD),
                Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
            ),
            tip = stringResource(R.string.perm_floating_tip),
        )
        PermissionCard(
            info = info,
            status = stringResource(
                if (floatingEnabled || debugOverlayEnabled) R.string.perm_status_on else R.string.perm_status_off,
            ),
            ready = floatingEnabled || debugOverlayEnabled,
            onShowInfo = { infoSheet = info },
            action = {
                Column {
                    SwitchRow(
                        label = stringResource(R.string.perm_floating_control),
                        checked = floatingEnabled,
                    ) { checked ->
                        if (checked) {
                            withOverlay { FloatingWindowService.start(context) }
                        } else {
                            FloatingWindowService.stop(context)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SwitchRow(
                        label = stringResource(R.string.perm_floating_debug),
                        checked = debugOverlayEnabled,
                        description = stringResource(R.string.perm_floating_debug_desc),
                    ) { checked ->
                        if (checked) {
                            withOverlay { DebugOverlayService.start(context) }
                        } else {
                            DebugOverlayService.stop(context)
                        }
                    }
                    if (!canDrawOverlays) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.perm_overlay_needed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )
    }

    @Composable
    fun UsageAccessCard() {
        val info = PermissionInfo(
            title = stringResource(R.string.perm_usage_title),
            description = stringResource(R.string.perm_usage_desc),
            dimensions = listOf(
                Dimension(stringResource(R.string.dim_accuracy), stringResource(R.string.rating_high), Rating.GOOD),
                Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_low), Rating.GOOD),
                Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
            ),
        )
        PermissionCard(
            info = info,
            status = stringResource(
                if (usageAccessGranted) R.string.perm_status_authorized else R.string.perm_status_unauthorized,
            ),
            ready = usageAccessGranted,
            onShowInfo = { infoSheet = info },
            action = {
                SwitchRow(
                    label = stringResource(R.string.perm_switch_grant),
                    checked = usageAccessGranted,
                ) { openSystemSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            },
        )
    }

    @Composable
    fun NotificationListenerCard() {
        val info = PermissionInfo(
            title = stringResource(R.string.perm_notify_title),
            description = stringResource(R.string.perm_notify_desc),
            dimensions = listOf(
                Dimension(stringResource(R.string.dim_accuracy), stringResource(R.string.rating_high), Rating.GOOD),
                Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_low), Rating.GOOD),
                Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
            ),
        )
        PermissionCard(
            info = info,
            status = stringResource(
                if (notificationListenerGranted) R.string.perm_status_authorized else R.string.perm_status_unauthorized,
            ),
            ready = notificationListenerGranted,
            onShowInfo = { infoSheet = info },
            action = {
                SwitchRow(
                    label = stringResource(R.string.perm_switch_grant),
                    checked = notificationListenerGranted,
                ) { openSystemSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            },
        )
    }

    @Composable
    fun AppListCard() {
        val enabled = runtimeSettings.appList
        val info = PermissionInfo(
            title = stringResource(R.string.perm_applist_title),
            description = stringResource(R.string.perm_applist_desc),
            dimensions = listOf(
                Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_low), Rating.GOOD),
                Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
            ),
        )
        PermissionCard(
            info = info,
            status = stringResource(if (enabled) R.string.perm_status_on else R.string.perm_status_off),
            ready = enabled,
            onShowInfo = { infoSheet = info },
            action = {
                SwitchRow(
                    label = stringResource(R.string.perm_applist_switch),
                    checked = enabled,
                ) { saveRuntimeSettings(runtimeSettings.copy(appList = it)) }
            },
        )
    }

    @Composable
    fun AllFilesCard() {
        val info = PermissionInfo(
            title = stringResource(R.string.perm_allfiles_title),
            description = stringResource(R.string.perm_allfiles_desc),
            dimensions = listOf(
                Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_medium), Rating.WARN),
                Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
            ),
        )
        PermissionCard(
            info = info,
            status = stringResource(
                if (allFilesGranted) R.string.perm_status_authorized else R.string.perm_status_unauthorized,
            ),
            ready = allFilesGranted,
            onShowInfo = { infoSheet = info },
            action = {
                SwitchRow(
                    label = stringResource(R.string.perm_switch_grant),
                    checked = allFilesGranted,
                ) {
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
                }
            },
        )
    }

    @Composable
    fun WakeCard() {
        val info = PermissionInfo(
            title = stringResource(R.string.perm_wake_title),
            description = stringResource(R.string.perm_wake_desc),
            dimensions = listOf(
                Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_low), Rating.GOOD),
            ),
        )
        PermissionCard(
            info = info,
            status = stringResource(
                if (runtimeSettings.keepAwake) R.string.perm_status_on else R.string.perm_status_off,
            ),
            ready = runtimeSettings.keepAwake,
            onShowInfo = { infoSheet = info },
            action = {
                Column {
                    SwitchRow(
                        label = stringResource(R.string.perm_wake_title),
                        checked = runtimeSettings.keepAwake,
                        description = stringResource(R.string.perm_wake_desc),
                    ) { saveRuntimeSettings(runtimeSettings.copy(keepAwake = it)) }
                    Spacer(Modifier.height(12.dp))
                    SwitchRow(
                        label = stringResource(R.string.perm_wake_screen),
                        checked = runtimeSettings.wakeScreen,
                        description = stringResource(R.string.perm_wake_screen_desc),
                        enabled = runtimeSettings.keepAwake,
                    ) { saveRuntimeSettings(runtimeSettings.copy(wakeScreen = it)) }
                }
            },
        )
    }

    @Composable
    fun TileCard() {
        val pinnedId by container.tileSettingsRepository.scriptId.collectAsStateWithLifecycle()
        var pinnedName by remember(pinnedId) { mutableStateOf<String?>(null) }
        LaunchedEffect(pinnedId) {
            pinnedName = pinnedId?.let { id ->
                withContext(Dispatchers.IO) { container.scriptRepository.find(id)?.name }
            }
        }
        val info = PermissionInfo(
            title = stringResource(R.string.perm_tile_title),
            description = stringResource(R.string.perm_tile_desc),
            dimensions = listOf(
                Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
            ),
        )
        PermissionCard(
            info = info,
            status = stringResource(
                if (runtimeSettings.tileEnabled) R.string.perm_status_on else R.string.perm_status_off,
            ),
            ready = runtimeSettings.tileEnabled && pinnedName != null,
            onShowInfo = { infoSheet = info },
            action = {
                Column {
                    SwitchRow(
                        label = stringResource(R.string.perm_tile_enable),
                        checked = runtimeSettings.tileEnabled,
                    ) { saveRuntimeSettings(runtimeSettings.copy(tileEnabled = it)) }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        pinnedName?.let { stringResource(R.string.perm_tile_pinned, it) }
                            ?: stringResource(R.string.perm_tile_pick_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.perm_tile_manual_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.run_permissions_title)) },
                scrollBehavior = scrollBehavior,
                colors = stableTopAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { shizukuManager.refresh(); permissionRefresh++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh))
                    }
                },
            )
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
                            ScreenCaptureCard()
                            Spacer(Modifier.height(12.dp))
                            AccessibilityCard()
                            Spacer(Modifier.height(12.dp))
                            ShizukuCard()
                            Spacer(Modifier.height(12.dp))
                            RootCard()
                            Spacer(Modifier.height(12.dp))
                            NotificationListenerCard()
                            Spacer(Modifier.height(12.dp))
                            UsageAccessCard()
                            Spacer(Modifier.height(12.dp))
                            AppListCard()
                        }

                        PermissionGroup.OPERATE -> {
                            HidCard()
                            Spacer(Modifier.height(12.dp))
                            AccessibilityCard()
                            Spacer(Modifier.height(12.dp))
                            ShizukuCard()
                            Spacer(Modifier.height(12.dp))
                            RootCard()
                        }

                        PermissionGroup.OTHER -> {
                            FloatingCard()
                            Spacer(Modifier.height(12.dp))
                            AllFilesCard()
                            Spacer(Modifier.height(12.dp))
                            WakeCard()
                            Spacer(Modifier.height(12.dp))
                            TileCard()
                        }
                    }
                }
            }
        }
    }

    infoSheet?.let { info ->
        PermissionInfoSheet(info = info, onDismiss = { infoSheet = null })
    }
}

/**
 * 顶部状态头：并排展示「读取」「操作」两个维度的整体授权状态。
 */
@Composable
private fun PermissionStatusHeader(readReady: Boolean, operateReady: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PermissionStatusPill(
            label = stringResource(R.string.perm_group_read),
            ready = readReady,
            modifier = Modifier.weight(1f),
        )
        PermissionStatusPill(
            label = stringResource(R.string.perm_group_operate),
            ready = operateReady,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PermissionStatusPill(label: String, ready: Boolean, modifier: Modifier = Modifier) {
    val extra = LocalIzukiExtraColors.current
    val container = if (ready) extra.successContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (ready) extra.success else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = content)
        Text(
            stringResource(if (ready) R.string.perm_status_authorized else R.string.perm_status_unauthorized),
            style = MaterialTheme.typography.titleMedium,
            color = content,
        )
    }
}

/**
 * 权限能力介绍：标题、说明、维度标签、优缺点或搭配建议。空字段自动隐藏。
 */
private data class PermissionInfo(
    val title: String,
    val description: String,
    val dimensions: List<Dimension> = emptyList(),
    val pros: List<String> = emptyList(),
    val cons: List<String> = emptyList(),
    val tip: String? = null,
)

/**
 * 权限卡片：标题 + 状态 + 说明入口（?）+ 操作区。详细介绍在 [PermissionInfoSheet] 中展开。
 */
@Composable
private fun PermissionCard(
    info: PermissionInfo,
    status: String,
    ready: Boolean,
    onShowInfo: () -> Unit,
    action: @Composable () -> Unit,
) {
    val extra = LocalIzukiExtraColors.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(if (ready) extra.success else MaterialTheme.colorScheme.outline, CircleShape),
                )
                Spacer(Modifier.size(8.dp))
                Text(info.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ready) extra.success else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                InfoButton(onClick = onShowInfo)
            }
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

/** 卡片右上角的「?」按钮，点击查看该权限能力的详细介绍。 */
@Composable
private fun InfoButton(onClick: () -> Unit) {
    val label = stringResource(R.string.perm_detail_open)
    // 外层撑到 48dp 作为触控热区（符合 Material 最小交互尺寸），视觉圆形仍保持 24dp。
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "?",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 权限能力介绍弹层：从底部升起，便于展示较长的说明与优缺点。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionInfoSheet(info: PermissionInfo, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(info.title, style = MaterialTheme.typography.titleLarge)
            Text(
                info.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (info.dimensions.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    info.dimensions.forEach { DimensionTag(it) }
                }
            }
            if (info.pros.isNotEmpty() || info.cons.isNotEmpty()) {
                ProsCons(pros = info.pros, cons = info.cons)
            }
            info.tip?.let { tip ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        tip,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) { Text(stringResource(R.string.perm_detail_close)) }
        }
    }
}

private enum class Rating { GOOD, WARN, BAD }

private data class Dimension(val label: String, val value: String, val rating: Rating)

@Composable
private fun DimensionTag(dimension: Dimension) {
    val extra = LocalIzukiExtraColors.current
    val (container, content) = when (dimension.rating) {
        Rating.GOOD -> extra.successContainer to extra.success
        Rating.WARN -> extra.warningContainer to extra.warning
        Rating.BAD -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            "${dimension.label} ${dimension.value}",
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

@Composable
private fun ProsCons(pros: List<String>, cons: List<String>) {
    val extra = LocalIzukiExtraColors.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        pros.forEach { text ->
            Text("✓ $text", style = MaterialTheme.typography.bodySmall, color = extra.success)
        }
        cons.forEach { text ->
            Text("✕ $text", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun CapabilityActions(
    actionLabel: String,
    onAction: (() -> Unit)?,
    onRevoke: (() -> Unit)?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { onAction?.invoke() }, enabled = onAction != null) {
            Text(actionLabel)
        }
        if (onRevoke != null) {
            OutlinedButton(onClick = { onRevoke.invoke() }) {
                Text(stringResource(R.string.perm_action_revoke))
            }
        }
    }
}

private const val SHIZUKU_REQUEST_CODE = 1001

/** 使用情况访问（PACKAGE_USAGE_STATS）是否已授予。 */
private fun hasUsageAccess(context: Context): Boolean {
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
private fun hasNotificationListener(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

/** 全部文件访问是否已授予。 */
private fun hasAllFilesAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    } else {
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
