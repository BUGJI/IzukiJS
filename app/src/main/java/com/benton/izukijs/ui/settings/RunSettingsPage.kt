package com.benton.izukijs.ui.settings

import com.benton.izukijs.ui.common.SectionGap
import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.benton.izukijs.core.permission.SystemPermissionChecks
import com.benton.izukijs.runtime.QuickStopGesture
import com.benton.izukijs.runtime.QuickStopKey
import com.benton.izukijs.runtime.RunBusyPolicy
import com.benton.izukijs.service.IzukiAccessibilityService
import com.benton.izukijs.service.ScriptTileService
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SwitchRow
import com.benton.izukijs.ui.appViewModel
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 脚本运行：超时、唤醒、通知与快捷磁贴。 */
@Composable
internal fun RunSettings() {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    TimeoutSettingsSection(ui, viewModel)

    Spacer(Modifier.height(SectionGap))
    BusySettingsSection(ui, viewModel)

    Spacer(Modifier.height(SectionGap))
    QuickStopSettingsSection(ui, viewModel)

    Spacer(Modifier.height(SectionGap))
    WakeSettingsSection(ui, viewModel)

    Spacer(Modifier.height(SectionGap))
    NotificationSettingsSection(ui, viewModel)

    Spacer(Modifier.height(SectionGap))
    TileSettingsSection(ui, viewModel)
}

@Composable
private fun TimeoutSettingsSection(ui: SettingsUiState, viewModel: SettingsViewModel) {
    val settings = ui.runtimeSettings

    SectionCard(stringResource(R.string.perm_timeout_title)) {
        Text(
            stringResource(R.string.perm_timeout_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ChipFlow {
            listOf(0, 10, 30, 60, 120).forEach { minutes ->
                FilterChip(
                    selected = settings.scriptTimeoutMinutes == minutes,
                    onClick = {
                        viewModel.saveRuntimeSettings(settings.copy(scriptTimeoutMinutes = minutes))
                    },
                    label = {
                        Text(
                            if (minutes == 0) {
                                stringResource(R.string.perm_timeout_unlimited)
                            } else {
                                stringResource(R.string.perm_timeout_minutes, minutes)
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun BusySettingsSection(ui: SettingsUiState, viewModel: SettingsViewModel) {
    val settings = ui.runtimeSettings

    SectionCard(stringResource(R.string.settings_busy_title)) {
        Text(
            stringResource(R.string.settings_busy_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ChipFlow {
            listOf(
                RunBusyPolicy.REJECT to R.string.settings_busy_reject,
                RunBusyPolicy.QUEUE to R.string.settings_busy_queue,
                RunBusyPolicy.TERMINATE to R.string.settings_busy_terminate,
            ).forEach { (policy, label) ->
                FilterChip(
                    selected = settings.busyPolicy == policy,
                    onClick = {
                        viewModel.saveRuntimeSettings(settings.copy(busyPolicy = policy))
                    },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        if (settings.busyPolicy == RunBusyPolicy.TERMINATE) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_busy_terminate_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun QuickStopSettingsSection(ui: SettingsUiState, viewModel: SettingsViewModel) {
    val settings = ui.runtimeSettings
    val snackbar = LocalSnackbarController.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    // 无障碍连接状态不是 StateFlow：页面恢复时重新读取，避免提示长期停留在旧值。
    var accessibilityConnected by remember {
        mutableStateOf(IzukiAccessibilityService.isConnected)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityConnected = IzukiAccessibilityService.isConnected
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SectionCard(stringResource(R.string.settings_quick_stop_title)) {
        Text(
            stringResource(R.string.settings_quick_stop_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            label = stringResource(R.string.settings_quick_stop_enable),
            checked = settings.volumeKeyStopEnabled,
            description = stringResource(R.string.settings_quick_stop_enable_desc),
        ) {
            viewModel.saveRuntimeSettings(settings.copy(volumeKeyStopEnabled = it))
        }

        if (!settings.volumeKeyStopEnabled) return@SectionCard

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.settings_quick_stop_key_title),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ChipFlow {
            listOf(
                QuickStopKey.VOLUME_DOWN to R.string.settings_quick_stop_key_down,
                QuickStopKey.VOLUME_UP to R.string.settings_quick_stop_key_up,
                QuickStopKey.BOTH to R.string.settings_quick_stop_key_both,
            ).forEach { (key, label) ->
                FilterChip(
                    selected = settings.volumeKeyStopKey == key,
                    onClick = {
                        viewModel.saveRuntimeSettings(settings.copy(volumeKeyStopKey = key))
                    },
                    label = { Text(stringResource(label)) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.settings_quick_stop_gesture_title),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ChipFlow {
            listOf(
                QuickStopGesture.SINGLE to R.string.settings_quick_stop_gesture_single,
                QuickStopGesture.DOUBLE to R.string.settings_quick_stop_gesture_double,
                QuickStopGesture.LONG to R.string.settings_quick_stop_gesture_long,
            ).forEach { (gesture, label) ->
                FilterChip(
                    selected = settings.volumeKeyStopGesture == gesture,
                    onClick = {
                        viewModel.saveRuntimeSettings(
                            settings.copy(volumeKeyStopGesture = gesture),
                        )
                        if (gesture != QuickStopGesture.SINGLE && !accessibilityConnected) {
                            scope.launch {
                                snackbar.show(
                                    resources.getString(
                                        R.string.settings_quick_stop_need_accessibility,
                                    ),
                                )
                            }
                        }
                    },
                    label = { Text(stringResource(label)) },
                )
            }
        }

        if (!accessibilityConnected) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    if (settings.volumeKeyStopGesture == QuickStopGesture.SINGLE) {
                        R.string.settings_quick_stop_fallback_hint
                    } else {
                        R.string.settings_quick_stop_need_accessibility
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (settings.volumeKeyStopGesture == QuickStopGesture.SINGLE) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

@Composable
private fun WakeSettingsSection(ui: SettingsUiState, viewModel: SettingsViewModel) {
    val settings = ui.runtimeSettings

    SectionCard {
        SwitchRow(
            label = stringResource(R.string.perm_wake_title),
            checked = settings.keepAwake,
            description = stringResource(R.string.perm_wake_desc),
        ) { viewModel.saveRuntimeSettings(settings.copy(keepAwake = it)) }
        Spacer(Modifier.height(12.dp))
        SwitchRow(
            label = stringResource(R.string.perm_wake_screen),
            checked = settings.wakeScreen,
            description = stringResource(R.string.perm_wake_screen_desc),
            enabled = settings.keepAwake,
        ) { viewModel.saveRuntimeSettings(settings.copy(wakeScreen = it)) }
    }
}

@Composable
private fun NotificationSettingsSection(ui: SettingsUiState, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(SystemPermissionChecks.notificationsEnabled(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = SystemPermissionChecks.notificationsEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SectionCard(stringResource(R.string.settings_notifications)) {
        Text(
            stringResource(R.string.settings_notifications_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            label = stringResource(R.string.settings_progress_notification),
            description = stringResource(R.string.settings_progress_notification_desc),
            checked = ui.runtimeSettings.progressNotification,
            onChange = {
                viewModel.saveRuntimeSettings(
                    ui.runtimeSettings.copy(progressNotification = it),
                )
            },
        )
        if (!granted) {
            Spacer(Modifier.height(SectionGap))
            Text(
                stringResource(R.string.settings_notification_permission_denied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(
                                    android.provider.Settings.EXTRA_APP_PACKAGE,
                                    context.packageName,
                                ),
                        )
                    }
                },
            ) {
                Text(stringResource(R.string.settings_notification_permission_action))
            }
        }
    }
}

@Composable
private fun TileSettingsSection(ui: SettingsUiState, viewModel: SettingsViewModel) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarController.current
    val settings = ui.runtimeSettings

    val pinnedId by container.tileSettingsRepository.scriptId.collectAsStateWithLifecycle()
    var pinnedName by remember(pinnedId) { mutableStateOf<String?>(null) }
    LaunchedEffect(pinnedId) {
        pinnedName = pinnedId?.let { id ->
            withContext(Dispatchers.IO) { container.scriptRepository.find(id)?.name }
        }
    }

    fun addTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = context.getSystemService(StatusBarManager::class.java)
        if (manager == null) {
            scope.launch { snackbar.show(resources.getString(R.string.perm_tile_add_failed)) }
            return
        }
        runCatching {
            manager.requestAddTileService(
                ComponentName(context, ScriptTileService::class.java),
                resources.getString(R.string.app_name),
                Icon.createWithResource(context, R.drawable.ic_stat_script),
                context.mainExecutor,
            ) { result ->
                val added =
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                        result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                scope.launch {
                    snackbar.show(
                        resources.getString(
                            if (added) R.string.perm_tile_added else R.string.perm_tile_add_failed,
                        ),
                    )
                }
            }
        }.onFailure {
            scope.launch { snackbar.show(resources.getString(R.string.perm_tile_add_failed)) }
        }
    }

    SectionCard(stringResource(R.string.perm_tile_title)) {
        Text(
            stringResource(R.string.perm_tile_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            label = stringResource(R.string.perm_tile_enable),
            checked = settings.tileEnabled,
        ) { viewModel.saveRuntimeSettings(settings.copy(tileEnabled = it)) }
        Spacer(Modifier.height(8.dp))
        Text(
            pinnedName?.let { stringResource(R.string.perm_tile_pinned, it) }
                ?: stringResource(R.string.perm_tile_pick_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            settings.tileEnabled &&
            pinnedName != null
        ) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = ::addTile) {
                Text(stringResource(R.string.perm_tile_add))
            }
        } else {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.perm_tile_manual_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
