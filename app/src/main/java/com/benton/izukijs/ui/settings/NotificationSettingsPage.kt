package com.benton.izukijs.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.core.notification.NotifyChannel
import com.benton.izukijs.core.notification.NotifyChannelType
import com.benton.izukijs.core.notification.NotifyKind
import com.benton.izukijs.core.notification.NotifyLevel
import com.benton.izukijs.ui.appViewModel
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SectionGap
import com.benton.izukijs.ui.common.SwitchRow
import kotlinx.coroutines.launch

/** 通知系统设置：全局开关、运行结果策略、渠道管理与测试。 */
@Composable
internal fun NotificationSettingsPage(
    onOpenChannelEditor: (String?) -> Unit = {},
) {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = ui.notificationSettings
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val snackbar = LocalSnackbarController.current

    var deleting by remember { mutableStateOf<NotifyChannel?>(null) }

    fun save(next: com.benton.izukijs.core.notification.NotificationSettings) =
        viewModel.saveNotificationSettings(next)

    val (granted, requestPermission) = rememberNotificationPermission()

    SectionCard(stringResource(R.string.notif_section_global)) {
        Text(
            stringResource(R.string.notif_global_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            label = stringResource(R.string.notif_global_enabled),
            description = stringResource(R.string.notif_global_enabled_desc),
            checked = settings.enabled,
        ) { save(settings.copy(enabled = it)) }
        if (!granted) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.notif_permission_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = requestPermission) {
                Text(stringResource(R.string.notif_permission_action))
            }
        }
    }

    Spacer(Modifier.height(SectionGap))
    SectionCard(stringResource(R.string.notif_section_script)) {
        SwitchRow(
            label = stringResource(R.string.notif_script_enabled),
            description = stringResource(R.string.notif_script_enabled_desc),
            checked = settings.scriptMessages,
            enabled = settings.enabled,
        ) { save(settings.copy(scriptMessages = it)) }
    }

    Spacer(Modifier.height(SectionGap))
    SectionCard(stringResource(R.string.notif_section_run)) {
        SwitchRow(
            label = stringResource(R.string.notif_run_manual),
            description = stringResource(R.string.notif_run_manual_desc),
            checked = settings.runManual,
            enabled = settings.enabled,
        ) { save(settings.copy(runManual = it)) }
        Spacer(Modifier.height(12.dp))
        SwitchRow(
            label = stringResource(R.string.notif_run_auto),
            description = stringResource(R.string.notif_run_auto_desc),
            checked = settings.runAuto,
            enabled = settings.enabled,
        ) { save(settings.copy(runAuto = it)) }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.notif_run_min_level),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.notif_run_min_level_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ChipFlow {
            listOf(
                NotifyLevel.INFO to R.string.notif_level_all,
                NotifyLevel.WARN to R.string.notif_level_failures,
                NotifyLevel.ERROR to R.string.notif_level_errors,
            ).forEach { (level, label) ->
                FilterChip(
                    selected = settings.runMinLevel == level,
                    enabled = settings.enabled,
                    onClick = { save(settings.copy(runMinLevel = level)) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        SwitchRow(
            label = stringResource(R.string.notif_flow_summary),
            description = stringResource(R.string.notif_flow_summary_desc),
            checked = settings.flowSummary,
            enabled = settings.enabled,
        ) { save(settings.copy(flowSummary = it)) }
        Spacer(Modifier.height(12.dp))
        SwitchRow(
            label = stringResource(R.string.notif_flow_steps),
            description = stringResource(R.string.notif_flow_steps_desc),
            checked = settings.flowSteps,
            enabled = settings.enabled,
        ) { save(settings.copy(flowSteps = it)) }
        Spacer(Modifier.height(12.dp))
        SwitchRow(
            label = stringResource(R.string.notif_error_tail),
            description = stringResource(R.string.notif_error_tail_desc),
            checked = settings.includeErrorTail,
            enabled = settings.enabled,
        ) { save(settings.copy(includeErrorTail = it)) }
    }

    Spacer(Modifier.height(SectionGap))
    SectionCard(
        title = stringResource(R.string.notif_section_channels),
        trailing = {
            TextButton(
                onClick = { onOpenChannelEditor(null) },
                enabled = settings.enabled,
            ) {
                Text(stringResource(R.string.notif_channel_add))
            }
        },
    ) {
        Text(
            stringResource(R.string.notif_channels_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (settings.channels.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.notif_channel_empty),
                style = MaterialTheme.typography.bodySmall,
            )
            return@SectionCard
        }
        settings.channels.forEach { channel ->
            Spacer(Modifier.height(12.dp))
            ChannelRow(
                channel = channel,
                enabled = settings.enabled,
                onToggle = { checked ->
                    save(
                        settings.copy(
                            channels = settings.channels.map {
                                if (it.id == channel.id) it.copy(enabled = checked) else it
                            },
                        ),
                    )
                },
                onTest = {
                    scope.launch {
                        val report = viewModel.sendNotificationTest(channel.id)
                        val message = if (report.ok) {
                            resources.getString(R.string.notif_channel_test_ok)
                        } else {
                            resources.getString(
                                R.string.notif_channel_test_failed,
                                report.failed.firstOrNull()?.error.orEmpty(),
                            )
                        }
                        snackbar.show(message)
                    }
                },
                onEdit = { onOpenChannelEditor(channel.id) },
                onDelete = { deleting = channel },
            )
        }
    }

    deleting?.let { channel ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.notif_channel_delete_title)) },
            text = {
                Text(stringResource(R.string.notif_channel_delete_message, channelDisplayName(channel)))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        save(settings.copy(channels = settings.channels.filterNot { it.id == channel.id }))
                    },
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun ChannelRow(
    channel: NotifyChannel,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column {
        SwitchRow(
            label = channelDisplayName(channel),
            description = stringResource(
                R.string.notif_channel_summary,
                channelTypeLabel(channel.type),
                levelLabel(channel.minLevel),
            ),
            checked = channel.enabled,
            enabled = enabled,
            onChange = onToggle,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onTest) {
                Text(stringResource(R.string.notif_channel_test))
            }
            TextButton(onClick = onEdit) { Text(stringResource(R.string.notif_channel_edit)) }
            TextButton(onClick = onDelete, enabled = channel.type != NotifyChannelType.LOCAL) {
                Text(stringResource(R.string.common_delete))
            }
        }
    }
}

@Composable
private fun rememberNotificationPermission(): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPostNotifications(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        granted = allowed
        // 用户选择“不再询问”后再次申请会静默失败，直接跳到系统通知设置。
        if (!allowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val activity = context as? Activity
            if (activity != null &&
                !activity.shouldShowRequestPermissionRationale(
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            ) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = hasPostNotifications(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val request = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    return granted to request
}

private fun hasPostNotifications(context: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

@Composable
internal fun channelDisplayName(channel: NotifyChannel): String =
    channel.name.ifBlank { channelTypeLabel(channel.type) }

@Composable
internal fun channelTypeLabel(type: NotifyChannelType): String = stringResource(
    when (type) {
        NotifyChannelType.LOCAL -> R.string.notif_type_local
        NotifyChannelType.WEBHOOK -> R.string.notif_type_webhook
        NotifyChannelType.BARK -> R.string.notif_type_bark
        NotifyChannelType.TELEGRAM -> R.string.notif_type_telegram
        NotifyChannelType.NTFY -> R.string.notif_type_ntfy
        NotifyChannelType.DINGTALK_GROUP -> R.string.notif_type_dingtalk_group
        NotifyChannelType.WEWORK_ROBOT -> R.string.notif_type_wework_robot
        NotifyChannelType.FEISHU_ROBOT -> R.string.notif_type_feishu_robot
        NotifyChannelType.SERVERCHAN -> R.string.notif_type_serverchan
        NotifyChannelType.PUSHPLUS -> R.string.notif_type_pushplus
        NotifyChannelType.GOTIFY -> R.string.notif_type_gotify
    },
)

@Composable
internal fun levelLabel(level: NotifyLevel): String = stringResource(
    when (level) {
        NotifyLevel.INFO -> R.string.notif_level_info
        NotifyLevel.SUCCESS -> R.string.notif_level_success
        NotifyLevel.WARN -> R.string.notif_level_warn
        NotifyLevel.ERROR -> R.string.notif_level_error
    },
)

@Composable
internal fun kindLabel(kind: NotifyKind): String = stringResource(
    when (kind) {
        NotifyKind.SCRIPT -> R.string.notif_kind_script
        NotifyKind.RUN_MANUAL -> R.string.notif_kind_run_manual
        NotifyKind.RUN_AUTO -> R.string.notif_kind_run_auto
        NotifyKind.FLOW_SUMMARY -> R.string.notif_kind_flow_summary
    },
)
