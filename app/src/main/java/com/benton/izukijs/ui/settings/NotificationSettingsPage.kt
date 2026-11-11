package com.benton.izukijs.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
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
import com.benton.izukijs.ui.common.LabeledField
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.SecretField
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SectionGap
import com.benton.izukijs.ui.common.SwitchRow
import kotlinx.coroutines.launch

/** 通知系统设置：全局开关、运行结果策略、渠道管理与测试。 */
@Composable
internal fun NotificationSettingsPage() {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = ui.notificationSettings
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val snackbar = LocalSnackbarController.current

    var editing by remember { mutableStateOf<NotifyChannel?>(null) }
    var creating by remember { mutableStateOf(false) }
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
            TextButton(onClick = { creating = true }, enabled = settings.enabled) {
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
                onEdit = { editing = channel },
                onDelete = { deleting = channel },
            )
        }
    }

    if (creating) {
        ChannelEditDialog(
            initial = NotifyChannel.newChannel(NotifyChannelType.WEBHOOK),
            isNew = true,
            onDismiss = { creating = false },
            onConfirm = { channel ->
                creating = false
                save(settings.copy(channels = settings.channels + channel))
            },
        )
    }
    editing?.let { channel ->
        ChannelEditDialog(
            initial = channel,
            isNew = false,
            onDismiss = { editing = null },
            onConfirm = { updated ->
                editing = null
                save(
                    settings.copy(
                        channels = settings.channels.map { if (it.id == updated.id) updated else it },
                    ),
                )
            },
        )
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
private fun ChannelEditDialog(
    initial: NotifyChannel,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (NotifyChannel) -> Unit,
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var typeExpanded by remember { mutableStateOf(false) }
    val availableTypes = if (isNew) {
        NotifyChannelType.entries.filter { it != NotifyChannelType.LOCAL }
    } else {
        NotifyChannelType.entries
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isNew) R.string.notif_new_title else R.string.notif_edit_title,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LabeledField(
                    label = stringResource(R.string.notif_channel_name),
                    value = draft.name,
                    placeholder = stringResource(R.string.notif_channel_name_hint),
                    onChange = { draft = draft.copy(name = it) },
                )
                if (isNew) {
                    Spacer(Modifier.height(8.dp))
                    Box {
                        OutlinedButton(
                            onClick = { typeExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(R.string.notif_channel_type) +
                                    ": " + channelTypeLabel(draft.type),
                            )
                        }
                        DropdownMenu(
                            expanded = typeExpanded,
                            onDismissRequest = { typeExpanded = false },
                        ) {
                            availableTypes.forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(channelTypeLabel(type)) },
                                    onClick = {
                                        typeExpanded = false
                                        draft = draft.copy(type = type)
                                    },
                                )
                            }
                        }
                    }
                }
                ChannelTypeFields(draft) { draft = it }

                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.notif_channel_min_level),
                    style = MaterialTheme.typography.bodyMedium,
                )
                ChipFlow {
                    NotifyLevel.entries.forEach { level ->
                        FilterChip(
                            selected = draft.minLevel == level,
                            onClick = { draft = draft.copy(minLevel = level) },
                            label = { Text(levelLabel(level)) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.notif_channel_kinds),
                    style = MaterialTheme.typography.bodyMedium,
                )
                ChipFlow {
                    NotifyKind.entries.forEach { kind ->
                        FilterChip(
                            selected = kind in draft.kinds,
                            onClick = {
                                val next = if (kind in draft.kinds) {
                                    draft.kinds - kind
                                } else {
                                    draft.kinds + kind
                                }
                                if (next.isNotEmpty()) draft = draft.copy(kinds = next)
                            },
                            label = { Text(kindLabel(kind)) },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberFieldRaw(
                        label = stringResource(R.string.notif_field_timeout),
                        value = draft.timeoutMs.toString(),
                        modifier = Modifier.weight(1f),
                        onChange = { input ->
                            input.toIntOrNull()?.let {
                                draft = draft.copy(timeoutMs = it.coerceIn(1_000, 60_000))
                            }
                        },
                    )
                    NumberFieldRaw(
                        label = stringResource(R.string.notif_field_retries),
                        value = draft.retries.toString(),
                        modifier = Modifier.weight(1f),
                        onChange = { input ->
                            input.toIntOrNull()?.let {
                                draft = draft.copy(retries = it.coerceIn(0, 5))
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun ChannelTypeFields(channel: NotifyChannel, onChange: (NotifyChannel) -> Unit) {
    when (channel.type) {
        NotifyChannelType.LOCAL -> Text(
            stringResource(R.string.notif_local_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        NotifyChannelType.WEBHOOK -> {
            LabeledField(
                label = stringResource(R.string.notif_field_url),
                value = channel.url,
                placeholder = "https://example.com/webhook",
                onChange = { onChange(channel.copy(url = it)) },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.notif_field_method),
                style = MaterialTheme.typography.bodyMedium,
            )
            ChipFlow {
                listOf("POST", "PUT", "GET").forEach { method ->
                    FilterChip(
                        selected = channel.method.equals(method, ignoreCase = true),
                        onClick = { onChange(channel.copy(method = method)) },
                        label = { Text(method) },
                    )
                }
            }
            MultiLineField(
                label = stringResource(R.string.notif_field_headers),
                value = channel.headers,
                onChange = { onChange(channel.copy(headers = it)) },
            )
            MultiLineField(
                label = stringResource(R.string.notif_field_body),
                value = channel.bodyTemplate,
                onChange = { onChange(channel.copy(bodyTemplate = it)) },
            )
        }

        NotifyChannelType.BARK -> {
            LabeledField(
                label = stringResource(R.string.notif_field_server),
                value = channel.url,
                placeholder = "https://api.day.app",
                onChange = { onChange(channel.copy(url = it)) },
            )
            SecretField(
                label = stringResource(R.string.notif_field_device_key),
                value = channel.deviceKey,
                onChange = { onChange(channel.copy(deviceKey = it)) },
            )
            LabeledField(
                label = stringResource(R.string.notif_field_group),
                value = channel.group,
                onChange = { onChange(channel.copy(group = it)) },
            )
            LabeledField(
                label = stringResource(R.string.notif_field_icon),
                value = channel.icon,
                onChange = { onChange(channel.copy(icon = it)) },
            )
            LabeledField(
                label = stringResource(R.string.notif_field_sound),
                value = channel.sound,
                onChange = { onChange(channel.copy(sound = it)) },
            )
        }

        NotifyChannelType.TELEGRAM -> {
            SecretField(
                label = stringResource(R.string.notif_field_bot_token),
                value = channel.botToken,
                onChange = { onChange(channel.copy(botToken = it)) },
            )
            LabeledField(
                label = stringResource(R.string.notif_field_chat_id),
                value = channel.chatId,
                onChange = { onChange(channel.copy(chatId = it)) },
            )
        }

        NotifyChannelType.NTFY -> {
            LabeledField(
                label = stringResource(R.string.notif_field_server),
                value = channel.url,
                placeholder = "https://ntfy.sh",
                onChange = { onChange(channel.copy(url = it)) },
            )
            LabeledField(
                label = stringResource(R.string.notif_field_topic),
                value = channel.topic,
                onChange = { onChange(channel.copy(topic = it)) },
            )
            LabeledField(
                label = stringResource(R.string.notif_field_priority),
                value = channel.priority,
                placeholder = "3",
                onChange = { onChange(channel.copy(priority = it)) },
            )
            SecretField(
                label = stringResource(R.string.notif_field_token),
                value = channel.token,
                onChange = { onChange(channel.copy(token = it)) },
            )
        }
    }
}

@Composable
private fun MultiLineField(label: String, value: String, onChange: (String) -> Unit) {
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
    )
}

@Composable
private fun NumberFieldRaw(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onChange(input.filter(Char::isDigit).take(6)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Number,
        ),
        modifier = modifier,
    )
}

@Composable
private fun rememberNotificationPermission(): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPostNotifications(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }
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
private fun channelDisplayName(channel: NotifyChannel): String =
    channel.name.ifBlank { channelTypeLabel(channel.type) }

@Composable
private fun channelTypeLabel(type: NotifyChannelType): String = stringResource(
    when (type) {
        NotifyChannelType.LOCAL -> R.string.notif_type_local
        NotifyChannelType.WEBHOOK -> R.string.notif_type_webhook
        NotifyChannelType.BARK -> R.string.notif_type_bark
        NotifyChannelType.TELEGRAM -> R.string.notif_type_telegram
        NotifyChannelType.NTFY -> R.string.notif_type_ntfy
    },
)

@Composable
private fun levelLabel(level: NotifyLevel): String = stringResource(
    when (level) {
        NotifyLevel.INFO -> R.string.notif_level_info
        NotifyLevel.SUCCESS -> R.string.notif_level_success
        NotifyLevel.WARN -> R.string.notif_level_warn
        NotifyLevel.ERROR -> R.string.notif_level_error
    },
)

@Composable
private fun kindLabel(kind: NotifyKind): String = stringResource(
    when (kind) {
        NotifyKind.SCRIPT -> R.string.notif_kind_script
        NotifyKind.RUN_MANUAL -> R.string.notif_kind_run_manual
        NotifyKind.RUN_AUTO -> R.string.notif_kind_run_auto
        NotifyKind.FLOW_SUMMARY -> R.string.notif_kind_flow_summary
    },
)
