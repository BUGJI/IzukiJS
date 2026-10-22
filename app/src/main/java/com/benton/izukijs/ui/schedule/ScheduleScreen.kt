package com.benton.izukijs.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.benton.izukijs.R
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.schedule.CronExpression
import com.benton.izukijs.schedule.Schedule
import com.benton.izukijs.schedule.ScheduleConditions
import com.benton.izukijs.schedule.ScheduleEvent
import com.benton.izukijs.schedule.ScheduleKind
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.EmptyState
import com.benton.izukijs.ui.common.formatMonthDayTime
import com.benton.izukijs.ui.common.stableTopAppBarColors
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(onBack: () -> Unit) {
    val container = rememberAppContainer()
    val scope = rememberCoroutineScope()
    var scripts by remember { mutableStateOf<List<ScriptInfo>>(emptyList()) }
    var schedules by remember { mutableStateOf<List<Schedule>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Schedule?>(null) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(Unit) {
        scripts = container.scriptRepository.listAsync()
        schedules = container.scheduleRepository.listAsync()
        loaded = true
    }

    fun refresh() {
        scope.launch { schedules = container.scheduleRepository.listAsync() }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.schedule_title)) },
                scrollBehavior = scrollBehavior,
                colors = stableTopAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.schedule_new))
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                // 首次进入仍在异步加载，先留空避免闪一下空状态。
                !loaded -> Unit
                schedules.isEmpty() -> EmptyState(stringResource(R.string.schedule_empty))
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
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete))
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.schedule_delete_title)) },
            text = { Text(stringResource(R.string.schedule_delete_confirm, target.scriptName)) },
            confirmButton = {
                TextButton(onClick = {
                    val id = target.id
                    deleteTarget = null
                    scope.launch {
                        container.scheduleManager.cancelAsync(id)
                        refresh()
                    }
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showAdd) {
        AddScheduleDialog(
            scripts = scripts,
            onDismiss = { showAdd = false },
            onConfirm = { draft ->
                val schedule = Schedule(
                    id = UUID.randomUUID().toString(),
                    scriptId = draft.script.id,
                    scriptName = draft.script.name,
                    triggerAtMillis = if (draft.kind == ScheduleKind.EVENT) {
                        0L
                    } else {
                        computeNextTrigger(draft.hour, draft.minute)
                    },
                    intervalMinutes = if (draft.kind == ScheduleKind.INTERVAL) draft.interval else 0L,
                    kind = draft.kind,
                    cron = draft.cron,
                    event = draft.event,
                    conditions = draft.conditions,
                    retryCount = draft.retryCount,
                    retryDelayMinutes = draft.retryDelay,
                )
                scope.launch {
                    container.scheduleManager.scheduleAsync(schedule)
                    refresh()
                    showAdd = false
                }
            },
        )
    }
}

@Composable
private fun AddScheduleDialog(
    scripts: List<ScriptInfo>,
    onDismiss: () -> Unit,
    onConfirm: (ScheduleDraft) -> Unit,
) {
    var selected by remember { mutableStateOf(scripts.firstOrNull()) }
    var scriptExpanded by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(ScheduleKind.TIME) }
    var hour by remember { mutableStateOf("8") }
    var minute by remember { mutableStateOf("0") }
    var interval by remember { mutableStateOf("60") }
    var cron by remember { mutableStateOf("0 8 * * *") }
    var event by remember { mutableStateOf(ScheduleEvent.CHARGING) }
    var conditionCharging by remember { mutableStateOf(false) }
    var conditionWifi by remember { mutableStateOf(false) }
    var conditionUnlocked by remember { mutableStateOf(false) }
    var foregroundPackage by remember { mutableStateOf("") }
    var retryCount by remember { mutableStateOf("0") }
    var retryDelay by remember { mutableStateOf("10") }
    var saving by remember { mutableStateOf(false) }

    // 脚本可能异步加载完成，到位后补上默认选中。
    LaunchedEffect(scripts) {
        if (selected == null) selected = scripts.firstOrNull()
    }

    val cronValid = kind != ScheduleKind.CRON || CronExpression.parse(cron) != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schedule_new)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
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
                                onClick = { selected = script; scriptExpanded = false },
                            )
                        }
                    }
                }

                ChipFlow {
                    ScheduleKind.entries.forEach { item ->
                        FilterChip(
                            selected = kind == item,
                            onClick = { kind = item },
                            label = { Text(stringResource(kindLabel(item))) },
                        )
                    }
                }

                when (kind) {
                    ScheduleKind.TIME, ScheduleKind.INTERVAL -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = hour,
                                onValueChange = { hour = it.filter(Char::isDigit).take(2) },
                                label = { Text(stringResource(R.string.schedule_hour)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = minute,
                                onValueChange = { minute = it.filter(Char::isDigit).take(2) },
                                label = { Text(stringResource(R.string.schedule_minute)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (kind == ScheduleKind.INTERVAL) {
                            OutlinedTextField(
                                value = interval,
                                onValueChange = { interval = it.filter(Char::isDigit).take(5) },
                                label = { Text(stringResource(R.string.schedule_interval)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    ScheduleKind.CRON -> {
                        OutlinedTextField(
                            value = cron,
                            onValueChange = { cron = it },
                            label = { Text(stringResource(R.string.schedule_cron_label)) },
                            placeholder = { Text(stringResource(R.string.schedule_cron_hint)) },
                            singleLine = true,
                            isError = cron.isNotBlank() && !cronValid,
                            supportingText = {
                                Text(
                                    if (cronValid) {
                                        stringResource(R.string.schedule_cron_hint)
                                    } else {
                                        stringResource(R.string.schedule_cron_invalid)
                                    },
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    ScheduleKind.EVENT -> {
                        ChipFlow {
                            ScheduleEvent.entries.forEach { item ->
                                FilterChip(
                                    selected = event == item,
                                    onClick = { event = item },
                                    label = { Text(stringResource(eventLabel(item))) },
                                )
                            }
                        }
                    }
                }

                Text(
                    stringResource(R.string.schedule_conditions),
                    style = MaterialTheme.typography.labelLarge,
                )
                ChipFlow {
                    FilterChip(
                        selected = conditionCharging,
                        onClick = { conditionCharging = !conditionCharging },
                        label = { Text(stringResource(R.string.schedule_condition_charging)) },
                    )
                    FilterChip(
                        selected = conditionWifi,
                        onClick = { conditionWifi = !conditionWifi },
                        label = { Text(stringResource(R.string.schedule_condition_wifi)) },
                    )
                    FilterChip(
                        selected = conditionUnlocked,
                        onClick = { conditionUnlocked = !conditionUnlocked },
                        label = { Text(stringResource(R.string.schedule_condition_unlocked)) },
                    )
                }
                OutlinedTextField(
                    value = foregroundPackage,
                    onValueChange = { foregroundPackage = it.trim() },
                    label = { Text(stringResource(R.string.schedule_foreground_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

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
                    onConfirm(
                        ScheduleDraft(
                            script = script,
                            kind = kind,
                            hour = hour.toIntOrNull()?.coerceIn(0, 23) ?: 8,
                            minute = minute.toIntOrNull()?.coerceIn(0, 59) ?: 0,
                            interval = interval.toLongOrNull()?.coerceAtLeast(1L) ?: 60L,
                            cron = cron.trim(),
                            event = if (kind == ScheduleKind.EVENT) event else null,
                            conditions = ScheduleConditions(
                                charging = conditionCharging,
                                wifi = conditionWifi,
                                unlocked = conditionUnlocked,
                                foregroundPackage = foregroundPackage.trim(),
                            ),
                            retryCount = retryCount.toIntOrNull()?.coerceIn(0, 10) ?: 0,
                            retryDelay = retryDelay.toLongOrNull()?.coerceIn(1L, 1440L) ?: 10L,
                        ),
                    )
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
    val hour: Int,
    val minute: Int,
    val interval: Long,
    val cron: String,
    val event: ScheduleEvent?,
    val conditions: ScheduleConditions,
    val retryCount: Int,
    val retryDelay: Long,
)

private fun kindLabel(kind: ScheduleKind): Int = when (kind) {
    ScheduleKind.TIME -> R.string.schedule_kind_time
    ScheduleKind.INTERVAL -> R.string.schedule_kind_interval
    ScheduleKind.CRON -> R.string.schedule_kind_cron
    ScheduleKind.EVENT -> R.string.schedule_kind_event
}

private fun eventLabel(event: ScheduleEvent): Int = when (event) {
    ScheduleEvent.CHARGING -> R.string.schedule_event_charging
    ScheduleEvent.WIFI -> R.string.schedule_event_wifi
    ScheduleEvent.UNLOCK -> R.string.schedule_event_unlock
}

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
            stringResource(eventLabel(schedule.event ?: ScheduleEvent.CHARGING)),
        )
    }
    val conditionParts = buildList {
        if (schedule.conditions.charging) add(stringResource(R.string.schedule_condition_charging))
        if (schedule.conditions.wifi) add(stringResource(R.string.schedule_condition_wifi))
        if (schedule.conditions.unlocked) add(stringResource(R.string.schedule_condition_unlocked))
        if (schedule.conditions.foregroundPackage.isNotBlank()) {
            add("前台=${schedule.conditions.foregroundPackage}")
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

private fun computeNextTrigger(hour: Int, minute: Int): Long {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (target.timeInMillis <= now.timeInMillis) {
        target.add(Calendar.DAY_OF_YEAR, 1)
    }
    return target.timeInMillis
}
