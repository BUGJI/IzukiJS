package com.benton.izukijs.ui.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.benton.izukijs.R
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.schedule.Schedule
import com.benton.izukijs.schedule.ScheduleConditions
import com.benton.izukijs.schedule.ScheduleEvent
import com.benton.izukijs.schedule.ScheduleKind
import com.benton.izukijs.ui.automation.TriggerEditor
import com.benton.izukijs.ui.automation.TriggerEditorState
import com.benton.izukijs.ui.automation.TriggerEditorStateSaver

import com.benton.izukijs.ui.automation.scheduleEventLabel
import com.benton.izukijs.ui.automation.scheduleKindLabel
import com.benton.izukijs.ui.common.ConfirmDialog
import com.benton.izukijs.ui.common.EmptyState
import com.benton.izukijs.ui.common.IzukiPageScaffold
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.PageMaxWidth
import com.benton.izukijs.ui.common.formatMonthDayTime
import com.benton.izukijs.ui.common.rememberAppHaptics
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(onBack: () -> Unit) {
    val container = rememberAppContainer()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = LocalSnackbarController.current
    val haptics = rememberAppHaptics()
    var scripts by remember { mutableStateOf<List<ScriptInfo>>(emptyList()) }
    var schedules by remember { mutableStateOf<List<Schedule>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var editTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    val editTarget = schedules.firstOrNull { it.id == editTargetId }
    var deleteTarget by remember { mutableStateOf<Schedule?>(null) }

    LaunchedEffect(Unit) {
        scripts = container.scriptRepository.listAsync()
        schedules = container.scheduleRepository.listAsync()
        loaded = true
    }

    // 一次性任务触发后会被调度器删除；页面重新可见时刷新，避免继续展示过期条目。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { schedules = container.scheduleRepository.listAsync() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun refresh() {
        scope.launch { schedules = container.scheduleRepository.listAsync() }
    }

    IzukiPageScaffold(
        title = { Text(stringResource(R.string.schedule_title)) },
        onBack = onBack,
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.schedule_new))
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier.widthIn(max = PageMaxWidth).fillMaxWidth().fillMaxHeight(),
            ) {
                when {
                    // 首次进入仍在异步加载，先留空避免闪一下空状态。
                    !loaded -> Unit
                    schedules.isEmpty() -> EmptyState(
                        text = stringResource(R.string.schedule_empty),
                        icon = Icons.Filled.Add,
                        actionLabel = stringResource(R.string.schedule_new),
                        onAction = { showAdd = true },
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // 底部预留 FAB 高度，避免最后一条的删除按钮被遮挡。
                        contentPadding = PaddingValues(bottom = 88.dp),
                    ) {
                        items(schedules, key = { it.id }) { schedule ->
                            ListItem(
                                headlineContent = { Text(schedule.scriptName) },
                                supportingContent = { Text(describe(schedule)) },
                                trailingContent = {
                                    IconButton(onClick = { deleteTarget = schedule }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.common_delete),
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { editTargetId = schedule.id },
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.schedule_delete_title),
            message = stringResource(R.string.schedule_delete_confirm, target.scriptName),
            confirmLabel = stringResource(R.string.common_delete),
            destructive = true,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                val id = target.id
                haptics.reject()
                deleteTarget = null
                scope.launch {
                    container.scheduleManager.cancelAsync(id)
                    refresh()
                }
            },
        )
    }

    if (showAdd || editTarget != null) {
        val initial = editTarget
        AddScheduleDialog(
            scripts = scripts,
            initial = initial,
            onDismiss = {
                showAdd = false
                editTargetId = null
            },
            onConfirm = { draft, done ->
                val triggerAt = draft.triggerAtMillis
                val schedule = initial?.copy(
                    scriptId = draft.script.id,
                    scriptName = draft.script.name,
                    triggerAtMillis = triggerAt,
                    intervalMinutes = if (draft.kind == ScheduleKind.INTERVAL) draft.interval else 0L,
                    kind = draft.kind,
                    cron = draft.cron,
                    event = draft.event,
                    conditions = draft.conditions,
                    retryCount = draft.retryCount,
                    retryDelayMinutes = draft.retryDelay,
                    retryAttempt = 0,
                ) ?: Schedule(
                    id = UUID.randomUUID().toString(),
                    scriptId = draft.script.id,
                    scriptName = draft.script.name,
                    triggerAtMillis = triggerAt,
                    intervalMinutes = if (draft.kind == ScheduleKind.INTERVAL) draft.interval else 0L,
                    kind = draft.kind,
                    cron = draft.cron,
                    event = draft.event,
                    conditions = draft.conditions,
                    retryCount = draft.retryCount,
                    retryDelayMinutes = draft.retryDelay,
                )
                scope.launch {
                    val ok = try {
                        // scheduleAsync 按 id 覆盖并重设 alarm，不再先删除旧任务。
                        container.scheduleManager.scheduleAsync(schedule)
                        schedules = container.scheduleRepository.listAsync()
                        true
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        snackbar.show(context.getString(R.string.editor_save_failed))
                        false
                    }
                    done(ok)
                }
            },
        )
    }
}

@Composable
private fun AddScheduleDialog(
    scripts: List<ScriptInfo>,
    initial: Schedule?,
    onDismiss: () -> Unit,
    onConfirm: (ScheduleDraft, (Boolean) -> Unit) -> Unit,
) {
    val trigger by rememberSaveable(initial?.id, stateSaver = TriggerEditorStateSaver) {
        mutableStateOf(initial?.let { TriggerEditorState.from(it) } ?: TriggerEditorState.empty())
    }
    var selectedId by rememberSaveable(initial?.id) {
        mutableStateOf(initial?.scriptId ?: scripts.firstOrNull()?.id.orEmpty())
    }
    val selected = scripts.firstOrNull { it.id == selectedId } ?: scripts.firstOrNull()
    var scriptExpanded by rememberSaveable { mutableStateOf(false) }
    var retryCount by rememberSaveable(initial?.id) {
        mutableStateOf((initial?.retryCount ?: 0).toString())
    }
    var retryDelay by rememberSaveable(initial?.id) {
        mutableStateOf((initial?.retryDelayMinutes ?: 10L).coerceAtLeast(1L).toString())
    }
    var saving by remember { mutableStateOf(false) }

    // 脚本可能异步加载完成，到位后补上默认选中。
    LaunchedEffect(scripts) {
        if (selectedId.isBlank()) selectedId = scripts.firstOrNull()?.id.orEmpty()
    }

    val cronValid = trigger.cronValid

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (initial != null) R.string.schedule_edit_title else R.string.schedule_new,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box {
                    OutlinedButton(
                        onClick = { scriptExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(selected?.name ?: stringResource(R.string.schedule_select_script))
                    }
                    DropdownMenu(
                        expanded = scriptExpanded,
                        onDismissRequest = { scriptExpanded = false },
                    ) {
                        scripts.forEach { script ->
                            DropdownMenuItem(
                                text = { Text(script.name) },
                                onClick = { selectedId = script.id; scriptExpanded = false },
                            )
                        }
                    }
                }
                if (scripts.isEmpty()) {
                    Text(
                        stringResource(R.string.schedule_no_scripts),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                TriggerEditor(trigger)


                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = retryCount,
                        onValueChange = { retryCount = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.schedule_retry_count)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = retryDelay,
                        onValueChange = { retryDelay = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.schedule_retry_delay)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val script = selected ?: return@TextButton
                    saving = true
                    val draft = ScheduleDraft(
                        script = script,
                        kind = trigger.kind,
                        triggerAtMillis = trigger.triggerAtMillis(),
                        hour = trigger.hourInt(),
                        minute = trigger.minuteInt(),
                        interval = trigger.intervalLong(),
                        cron = trigger.cron.trim(),
                        event = trigger.eventOrNull(),
                        conditions = trigger.conditions(),
                        retryCount = retryCount.toIntOrNull()?.coerceIn(0, 10) ?: 0,
                        retryDelay = retryDelay.toLongOrNull()?.coerceIn(1L, 1440L) ?: 10L,
                    )
                    onConfirm(draft) { ok ->
                        saving = false
                        if (ok) onDismiss()
                    }
                },
                enabled = selected != null && !saving && cronValid,
            ) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

private data class ScheduleDraft(
    val script: ScriptInfo,
    val kind: ScheduleKind,
    val triggerAtMillis: Long,
    val hour: Int,
    val minute: Int,
    val interval: Long,
    val cron: String,
    val event: ScheduleEvent?,
    val conditions: ScheduleConditions,
    val retryCount: Int,
    val retryDelay: Long,
)

@Composable
private fun describe(schedule: Schedule): String {
    val time = formatMonthDayTime(schedule.triggerAtMillis)
    val base = when (schedule.kind) {
        ScheduleKind.TIME -> stringResource(R.string.schedule_next_once, time)
        ScheduleKind.INTERVAL ->
            stringResource(R.string.schedule_next_interval, time, schedule.intervalMinutes)

        ScheduleKind.CRON ->
            stringResource(R.string.schedule_next_cron, schedule.cron, time)

        ScheduleKind.EVENT -> stringResource(
            R.string.schedule_event_desc,
            scheduleEventLabel(schedule.event ?: ScheduleEvent.CHARGING),
        )
    }
    val conditionParts = buildList {
        if (schedule.conditions.charging) add(stringResource(R.string.schedule_condition_charging))
        if (schedule.conditions.wifi) add(stringResource(R.string.schedule_condition_wifi))
        if (schedule.conditions.unlocked) add(stringResource(R.string.schedule_condition_unlocked))
        if (schedule.conditions.foregroundPackage.isNotBlank()) {
            add(
                stringResource(
                    R.string.schedule_foreground_condition,
                    schedule.conditions.foregroundPackage,
                ),
            )
        }
    }
    val conditions = if (conditionParts.isEmpty()) {
        ""
    } else {
        stringResource(R.string.schedule_conditions_suffix, conditionParts.joinToString(" / "))
    }
    val retry = if (schedule.retryCount > 0) {
        stringResource(
            R.string.schedule_retry_suffix,
            schedule.retryCount,
            schedule.retryDelayMinutes,
        )
    } else {
        ""
    }
    return base + conditions + retry
}
