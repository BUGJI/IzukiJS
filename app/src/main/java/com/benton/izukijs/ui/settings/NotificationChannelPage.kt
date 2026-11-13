package com.benton.izukijs.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.core.notification.NotifyChannel
import com.benton.izukijs.core.notification.NotifyChannelSpecs
import com.benton.izukijs.core.notification.NotifyChannelType
import com.benton.izukijs.core.notification.NotifyKind
import com.benton.izukijs.core.notification.NotifyLevel
import com.benton.izukijs.ui.appViewModel
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.ConfirmDialog
import com.benton.izukijs.ui.common.IzukiPageScaffold
import com.benton.izukijs.ui.common.LabeledField
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.PageColumn
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SectionGap
import com.benton.izukijs.ui.common.SecretField
import com.benton.izukijs.ui.common.SwitchRow
import kotlinx.coroutines.launch

/** 单个推送渠道的新增 / 编辑页。字段较多，使用独立页面避免弹窗内滚动拥挤。 */
@Composable
internal fun NotificationChannelPage(
    channelId: String?,
    onBack: () -> Unit,
) {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbar = LocalSnackbarController.current
    val scope = rememberCoroutineScope()
    val isNew = channelId == null

    val initial = remember(channelId) {
        if (channelId == null) {
            NotifyChannel.newChannel(NotifyChannelType.WEBHOOK)
        } else {
            viewModel.notificationChannel(channelId)
        }
    }

    if (initial == null) {
        IzukiPageScaffold(
            title = { Text(stringResource(R.string.notif_edit_title)) },
            onBack = onBack,
        ) { padding ->
            PageColumn(modifier = Modifier.padding(padding).padding(horizontal = 16.dp)) {
                SectionCard {
                    Text(
                        stringResource(R.string.notif_channel_missing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onBack) {
                        Text(stringResource(R.string.common_back))
                    }
                }
            }
        }
        return
    }

    var draft by remember(channelId) { mutableStateOf(initial) }
    var timeoutText by remember(channelId) { mutableStateOf(initial.timeoutMs.toString()) }
    var retriesText by remember(channelId) { mutableStateOf(initial.retries.toString()) }
    var typeExpanded by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }

    val settings = ui.notificationSettings
    val dirty = draft != initial ||
        timeoutText != initial.timeoutMs.toString() ||
        retriesText != initial.retries.toString()

    val canSave = when (draft.type) {
        NotifyChannelType.LOCAL -> true
        NotifyChannelType.WEBHOOK -> draft.url.isNotBlank()
        NotifyChannelType.BARK -> draft.deviceKey.isNotBlank()
        NotifyChannelType.TELEGRAM -> draft.botToken.isNotBlank() && draft.chatId.isNotBlank()
        NotifyChannelType.NTFY -> draft.topic.isNotBlank()
        else -> NotifyChannelSpecs.of(draft.type)?.isValid(draft.config) ?: true
    }

    fun normalizedDraft(): NotifyChannel {
        val timeout = timeoutText.toIntOrNull()?.coerceIn(1_000, 60_000) ?: draft.timeoutMs
        val retries = retriesText.toIntOrNull()?.coerceIn(0, 5) ?: draft.retries
        return draft.copy(timeoutMs = timeout, retries = retries)
    }

    fun commit() {
        val next = normalizedDraft()
        val channels = if (isNew) {
            settings.channels + next
        } else {
            settings.channels.map { if (it.id == next.id) next else it }
        }
        viewModel.saveNotificationSettings(settings.copy(channels = channels))
        onBack()
    }

    fun requestBack() {
        if (dirty) showDiscard = true else onBack()
    }

    fun sendTest() {
        val channel = normalizedDraft()
        scope.launch {
            val report = viewModel.sendNotificationTest(channel)
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
    }

    BackHandler(enabled = dirty) { requestBack() }

    IzukiPageScaffold(
        title = {
            Text(stringResource(if (isNew) R.string.notif_new_title else R.string.notif_edit_title))
        },
        onBack = { requestBack() },
        actions = {
            TextButton(onClick = { sendTest() }) {
                Text(stringResource(R.string.notif_channel_test))
            }
            TextButton(onClick = { commit() }, enabled = canSave) {
                Text(stringResource(R.string.common_save))
            }
        },
    ) { padding ->
        PageColumn(modifier = Modifier.padding(padding).padding(horizontal = 16.dp)) {
            SectionCard(stringResource(R.string.notif_section_channel_basic)) {
                LabeledField(
                    label = stringResource(R.string.notif_channel_name),
                    value = draft.name,
                    placeholder = stringResource(R.string.notif_channel_name_hint),
                    onChange = { draft = draft.copy(name = it) },
                )
                if (isNew) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.notif_channel_type),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box {
                        OutlinedButton(
                            onClick = { typeExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(channelTypeLabel(draft.type))
                        }
                        DropdownMenu(
                            expanded = typeExpanded,
                            onDismissRequest = { typeExpanded = false },
                        ) {
                            NotifyChannelType.entries
                                .filter { it != NotifyChannelType.LOCAL }
                                .forEach { type ->
                                    DropdownMenuItem(
                                        text = { Text(channelTypeLabel(type)) },
                                        onClick = {
                                            typeExpanded = false
                                            draft = NotifyChannel.newChannel(type).copy(
                                                id = draft.id,
                                                name = draft.name,
                                                enabled = draft.enabled,
                                                minLevel = draft.minLevel,
                                                kinds = draft.kinds,
                                                tags = draft.tags,
                                                timeoutMs = draft.timeoutMs,
                                                retries = draft.retries,
                                            )
                                        },
                                    )
                                }
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.notif_channel_type) +
                            " · " + channelTypeLabel(draft.type),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    label = stringResource(R.string.notif_channel_enabled),
                    description = stringResource(R.string.notif_channel_enabled_desc),
                    checked = draft.enabled,
                ) { draft = draft.copy(enabled = it) }
            }

            Spacer(Modifier.height(SectionGap))
            SectionCard(stringResource(R.string.notif_section_channel_config)) {
                ChannelTypeFields(draft) { draft = it }
            }

            Spacer(Modifier.height(SectionGap))
            SectionCard(stringResource(R.string.notif_section_channel_delivery)) {
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
                        value = timeoutText,
                        modifier = Modifier.weight(1f),
                        onChange = { input ->
                            timeoutText = input
                            input.toIntOrNull()?.takeIf { it in 1_000..60_000 }?.let {
                                draft = draft.copy(timeoutMs = it)
                            }
                        },
                    )
                    NumberFieldRaw(
                        label = stringResource(R.string.notif_field_retries),
                        value = retriesText,
                        modifier = Modifier.weight(1f),
                        onChange = { input ->
                            retriesText = input
                            input.toIntOrNull()?.takeIf { it in 0..5 }?.let {
                                draft = draft.copy(retries = it)
                            }
                        },
                    )
                }
            }
        }
    }

    if (showDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.script_edit_discard_title),
            message = stringResource(R.string.script_edit_discard_message),
            confirmLabel = stringResource(R.string.script_edit_discard),
            onConfirm = {
                showDiscard = false
                onBack()
            },
            onDismiss = { showDiscard = false },
        )
    }
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
            val setConfig: (String, String) -> Unit = { key, value ->
                onChange(channel.copy(config = channel.config + (key to value)))
            }
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
                listOf("POST", "PUT", "PATCH", "GET").forEach { method ->
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
            SecretField(
                label = stringResource(R.string.notif_field_secret),
                value = channel.config["secret"].orEmpty(),
                onChange = { setConfig("secret", it) },
            )
            LabeledField(
                label = stringResource(R.string.notif_field_response),
                value = channel.config["response"].orEmpty(),
                onChange = { setConfig("response", it) },
            )
        }

        NotifyChannelType.BARK -> {
            val setConfig: (String, String) -> Unit = { key, value ->
                onChange(channel.copy(config = channel.config + (key to value)))
            }
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
            LabeledField(
                label = stringResource(R.string.notif_field_title),
                value = channel.config["title"].orEmpty(),
                onChange = { setConfig("title", it) },
            )
            LabeledField(
                label = stringResource(R.string.notif_field_badge),
                value = channel.config["badge"].orEmpty(),
                onChange = { setConfig("badge", it) },
            )
            LabeledField(
                label = stringResource(R.string.notif_field_url_action),
                value = channel.config["urlAction"].orEmpty(),
                placeholder = "https://example.com",
                onChange = { setConfig("urlAction", it) },
            )
            LabeledField(
                label = stringResource(R.string.notif_field_call),
                value = channel.config["call"].orEmpty(),
                onChange = { setConfig("call", it) },
            )
            LabeledField(
                label = stringResource(R.string.notif_field_auto_copy),
                value = channel.config["autoCopy"].orEmpty(),
                onChange = { setConfig("autoCopy", it) },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.notif_field_level),
                style = MaterialTheme.typography.bodyMedium,
            )
            ChipFlow {
                listOf("active", "passive", "timeSensitive", "critical").forEach { level ->
                    FilterChip(
                        selected = channel.config["level"].orEmpty() == level,
                        onClick = { setConfig("level", level) },
                        label = { Text(level) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.notif_field_transformation),
                style = MaterialTheme.typography.bodyMedium,
            )
            ChipFlow {
                listOf(
                    "none",
                    "AES128/CBC/PKCS7Padding",
                    "AES128/ECB/PKCS7Padding",
                    "AES256/CBC/PKCS7Padding",
                    "AES256/ECB/PKCS7Padding",
                    "AES256/GCM/NoPadding",
                ).forEach { transformation ->
                    FilterChip(
                        selected = channel.config["transformation"].orEmpty() == transformation,
                        onClick = { setConfig("transformation", transformation) },
                        label = { Text(transformation) },
                    )
                }
            }
            SecretField(
                label = stringResource(R.string.notif_field_encryption_key),
                value = channel.config["key"].orEmpty(),
                onChange = { setConfig("key", it) },
            )
            SecretField(
                label = stringResource(R.string.notif_field_iv),
                value = channel.config["iv"].orEmpty(),
                onChange = { setConfig("iv", it) },
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

        NotifyChannelType.DINGTALK_GROUP,
        NotifyChannelType.WEWORK_ROBOT,
        NotifyChannelType.FEISHU_ROBOT,
        NotifyChannelType.SERVERCHAN,
        NotifyChannelType.PUSHPLUS,
        NotifyChannelType.GOTIFY,
        -> SpecFields(channel, onChange)
    }
}

/** 按 [NotifyChannelSpecs] 动态渲染新渠道字段。 */
@Composable
private fun SpecFields(channel: NotifyChannel, onChange: (NotifyChannel) -> Unit) {
    val spec = NotifyChannelSpecs.of(channel.type) ?: return
    spec.fields.forEach { field ->
        val value = channel.config[field.key].orEmpty()
        val update: (String) -> Unit = { input ->
            onChange(channel.copy(config = channel.config + (field.key to input)))
        }
        when {
            field.options.isNotEmpty() -> {
                Spacer(Modifier.height(8.dp))
                Text(fieldLabel(field.label), style = MaterialTheme.typography.bodyMedium)
                ChipFlow {
                    field.options.forEach { option ->
                        FilterChip(
                            selected = value == option,
                            onClick = { update(option) },
                            label = { Text(optionLabel(option)) },
                        )
                    }
                }
            }

            field.multiline -> MultiLineField(
                label = fieldLabel(field.label),
                value = value,
                onChange = update,
            )

            field.secret -> SecretField(
                label = fieldLabel(field.label),
                value = value,
                onChange = update,
            )

            else -> LabeledField(
                label = fieldLabel(field.label),
                value = value,
                onChange = update,
            )
        }
    }
}

@Composable
private fun fieldLabel(key: String): String = stringResource(
    when (key) {
        "token" -> R.string.notif_field_token
        "secret" -> R.string.notif_field_secret
        "msg_type" -> R.string.notif_field_msg_type
        "at_all" -> R.string.notif_field_at_all
        "at_mobiles" -> R.string.notif_field_at_mobiles
        "at_dingtalk_ids" -> R.string.notif_field_at_dingtalk_ids
        "at_user_ids" -> R.string.notif_field_at_user_ids
        "at_open_ids" -> R.string.notif_field_at_open_ids
        "title_template" -> R.string.notif_field_title_template
        "webhook" -> R.string.notif_field_webhook
        "send_key" -> R.string.notif_field_send_key
        "channel" -> R.string.notif_field_channel
        "openid" -> R.string.notif_field_openid
        "website" -> R.string.notif_field_website
        "topic" -> R.string.notif_field_topic
        "template" -> R.string.notif_field_template
        "callback_url" -> R.string.notif_field_callback_url
        "valid_time" -> R.string.notif_field_valid_time
        "server" -> R.string.notif_field_server
        "priority" -> R.string.notif_field_priority
        "title" -> R.string.notif_field_title
        "message_card" -> R.string.notif_field_message_card
        else -> R.string.notif_field_token
    },
)

@Composable
private fun optionLabel(option: String): String = when (option) {
    "true" -> stringResource(R.string.notif_option_on)
    "false" -> stringResource(R.string.notif_option_off)
    else -> option
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
