package com.benton.izukijs.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.controller.record.RecordingEntry
import com.benton.izukijs.controller.record.RecordingIssue
import com.benton.izukijs.ui.appViewModel
import com.benton.izukijs.ui.common.ActionButtons
import com.benton.izukijs.ui.common.ConfirmDialog
import com.benton.izukijs.ui.common.EmptyState
import com.benton.izukijs.ui.common.LabeledField
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.NumberField
import com.benton.izukijs.ui.common.SwitchRow
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SectionGap
import com.benton.izukijs.ui.common.formatDateTime
import com.benton.izukijs.ui.common.formatFileSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun RecordingSettingsPage(onOpenDetail: (String) -> Unit = {}) {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarController.current

    var entries by remember { mutableStateOf<List<RecordingEntry>>(emptyList()) }
    var totalBytes by remember { mutableStateOf(0L) }
    var loadFailed by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RecordingEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var recordingName by remember { mutableStateOf("") }
    var captureScreenshots by remember { mutableStateOf(true) }
    var captureContext by remember { mutableStateOf(true) }
    var sampleIntervalMs by remember { mutableStateOf(700) }

    fun refresh() {
        scope.launch {
            runCatching { viewModel.loadRecordings() }
                .onSuccess { loaded ->
                    entries = loaded
                    // 复用每条录制的目录大小缓存，避免再次遍历整个录制目录。
                    totalBytes = loaded.sumOf { it.sizeBytes }
                    loadFailed = false
                }
                .onFailure { loadFailed = true }
        }
    }

    // 首次进入、录制状态变化、以及从详情页返回（重新进入组合）时都刷新列表与占用。
    LaunchedEffect(ui.recordingActive) {
        refresh()
        if (ui.recordingActive) {
            starting = false
            stopping = false
            if (ui.recordingKind == "user") recordingName = ""
        }
        if (!ui.recordingActive) stopping = false
    }

    // 启动/停止失败或采集降级时给出可见反馈，而不是只在日志里留下一条记录。
    LaunchedEffect(ui.recordingIssue) {
        val issue = ui.recordingIssue ?: return@LaunchedEffect
        starting = false
        stopping = false
        viewModel.clearRecordingIssue()
        scope.launch {
            snackbar.show(
                resources.getString(
                    when (issue) {
                        RecordingIssue.START_REJECTED -> R.string.record_issue_start_rejected
                        RecordingIssue.START_FAILED -> R.string.record_page_start_failed
                        RecordingIssue.NO_INPUT_BACKEND -> R.string.record_issue_no_backend
                        RecordingIssue.NO_TOUCH_DEVICE -> R.string.record_issue_no_touch
                        RecordingIssue.SCREENSHOT_FAILED -> R.string.record_issue_screenshot
                        RecordingIssue.STOP_FAILED -> R.string.record_page_stop_failed
                    },
                ),
            )
        }
    }

    // 服务启动是异步的；若迟迟没有 active / issue（例如服务被系统拦截），避免按钮永久停在启动中。
    LaunchedEffect(starting, ui.recordingActive) {
        if (!starting || ui.recordingActive) return@LaunchedEffect
        delay(3_000L)
        if (!ui.recordingActive) starting = false
    }

    LaunchedEffect(stopping, ui.recordingActive) {
        if (!stopping || !ui.recordingActive) return@LaunchedEffect
        delay(3_000L)
        if (ui.recordingActive) stopping = false
    }

    // 引用 ui.readyModes 让后端变化触发重组；实际判断仍由 controllerFor 过滤停用后端。
    val canCaptureInput = ui.readyModes.isNotEmpty() && viewModel.canCaptureUserInput()
    val screenCaptureActive = ui.screenCaptureActive
    val userRecording = ui.recordingActive && ui.recordingKind == "user"
    val activeId = viewModel.currentRecordingId()
    val activeEntry = entries.firstOrNull { entry -> entry.meta.id == activeId }
    val activeName = activeEntry?.meta?.name ?: activeId.orEmpty()

    SectionCard(stringResource(R.string.settings_record)) {
        Text(
            stringResource(R.string.record_page_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (ui.recordingActive) {
                stringResource(R.string.record_page_active, activeName)
            } else {
                stringResource(R.string.record_page_inactive)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (ui.recordingActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (userRecording && ui.recordingInputSource.isNotBlank()) {
            Text(
                stringResource(
                    R.string.record_page_input_source,
                    when (ui.recordingInputSource) {
                        "shizuku" -> "Shizuku"
                        "shell" -> "Shell"
                        else -> stringResource(R.string.record_page_input_source_none)
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.record_page_total, entries.size, formatFileSize(totalBytes)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (loadFailed) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.record_page_load_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(SectionGap))
        ActionButtons {
            OutlinedButton(
                onClick = { refresh() },
                enabled = !busy,
            ) { Text(stringResource(R.string.common_refresh)) }
            OutlinedButton(
                onClick = { confirmClear = true },
                enabled = !busy && !ui.recordingActive && entries.isNotEmpty(),
            ) { Text(stringResource(R.string.record_page_clear)) }
            if (userRecording) {
                OutlinedButton(
                    onClick = {
                        stopping = true
                        viewModel.stopUserRecording()
                    },
                    enabled = !busy && !stopping,
                ) {
                    Text(
                        stringResource(
                            if (stopping) R.string.record_page_stopping
                            else R.string.record_page_stop_user,
                        ),
                    )
                }
            } else if (!ui.recordingActive) {
                OutlinedButton(
                    onClick = {
                        starting = true
                        viewModel.startUserRecording(
                            name = recordingName,
                            captureScreenshots = captureScreenshots,
                            sampleIntervalMs = sampleIntervalMs.toLong(),
                            captureContext = captureContext,
                        )
                    },
                    enabled = !busy && canCaptureInput && !starting,
                ) {
                    Text(
                        stringResource(
                            if (starting) R.string.record_page_starting
                            else R.string.record_page_start_user,
                        ),
                    )
                }
            }
        }
        if (ui.recordingActive) {
            Spacer(Modifier.height(8.dp))
            if (!userRecording) {
                Text(
                    stringResource(R.string.record_page_script_recording_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.record_page_active_delete_blocked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.record_page_user_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LabeledField(
            label = stringResource(R.string.record_page_name),
            value = recordingName,
            placeholder = "user-recording",
            onChange = { recordingName = it },
        )
        if (!canCaptureInput) {
            Text(
                stringResource(R.string.record_page_need_input_backend),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
        } else if (captureScreenshots && !screenCaptureActive) {
            Text(
                stringResource(R.string.record_page_capture_permission_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        SwitchRow(
            label = stringResource(R.string.record_page_capture_screenshots),
            checked = captureScreenshots,
            description = stringResource(R.string.record_page_capture_screenshots_desc),
            enabled = !ui.recordingActive,
        ) { captureScreenshots = it }
        Spacer(Modifier.height(4.dp))
        SwitchRow(
            label = stringResource(R.string.record_page_capture_context),
            checked = captureContext,
            description = stringResource(R.string.record_page_capture_context_desc),
            enabled = !ui.recordingActive,
        ) { captureContext = it }
        Spacer(Modifier.height(4.dp))
        NumberField(
            label = stringResource(R.string.record_page_sample_interval),
            value = sampleIntervalMs,
            range = 250..5000,
            commitOnBlur = true,
        ) { sampleIntervalMs = it }
        Text(
            stringResource(R.string.record_page_sample_interval_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(SectionGap))
    if (entries.isEmpty()) {
        EmptyState(text = stringResource(R.string.record_page_empty))
    } else {
        SectionCard {
            entries.forEachIndexed { index, entry ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                RecordingRow(
                    entry = entry,
                    active = entry.meta.id == activeId,
                    enabled = !busy && entry.meta.id != activeId,
                    onOpen = { onOpenDetail(entry.meta.id) },
                    onDelete = { pendingDelete = entry },
                )
            }
        }
    }

    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.record_page_delete),
            message = stringResource(R.string.record_page_delete_confirm, entry.meta.name),
            destructive = true,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                busy = true
                scope.launch {
                    val ok = try {
                        runCatching { viewModel.deleteRecording(entry.meta.id) }
                            .getOrDefault(false)
                    } finally {
                        busy = false
                    }
                    refresh()
                    snackbar.show(
                        resources.getString(
                            if (ok) R.string.record_page_deleted else R.string.record_page_failed,
                        ),
                    )
                }
            },
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.record_page_clear),
            message = stringResource(R.string.record_page_clear_confirm),
            destructive = true,
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                busy = true
                scope.launch {
                    val removed = try {
                        runCatching { viewModel.clearRecordings() }.getOrDefault(-1)
                    } finally {
                        busy = false
                    }
                    refresh()
                    snackbar.show(
                        resources.getString(
                            if (removed >= 0) R.string.record_page_cleared
                            else R.string.record_page_failed,
                        ),
                    )
                }
            },
        )
    }
}

@Composable
private fun RecordingRow(
    entry: RecordingEntry,
    active: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        // 录制仍在写入时 events.jsonl 尚未 flush，此时打开详情会显示不完整的时间线。
        modifier = Modifier.fillMaxWidth().clickable(enabled = !active, onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                entry.meta.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    if (entry.meta.kind == "user") R.string.record_page_user_kind
                    else R.string.record_page_script_kind,
                ) + " · " + stringResource(
                    R.string.record_page_item_meta,
                    formatDateTime(entry.meta.createdAt),
                    entry.meta.frameCount,
                    formatFileSize(entry.sizeBytes),
                ) + if (entry.meta.truncated) {
                    " · " + stringResource(R.string.record_page_item_truncated)
                } else {
                    ""
                } + if (!entry.meta.complete) {
                    " · " + stringResource(R.string.record_page_item_incomplete)
                } else {
                    ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (active) {
            Text(
                stringResource(R.string.record_page_active_short),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            TextButton(onClick = onDelete, enabled = enabled) {
                Text(stringResource(R.string.record_page_delete))
            }
        }
    }
}
