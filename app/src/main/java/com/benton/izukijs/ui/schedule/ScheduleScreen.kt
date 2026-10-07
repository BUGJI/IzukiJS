package com.benton.izukijs.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.schedule.Schedule
import com.benton.izukijs.ui.common.EmptyState
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(onBack: () -> Unit) {
    val container = rememberAppContainer()
    val scope = rememberCoroutineScope()
    var scripts by remember { mutableStateOf<List<ScriptInfo>>(emptyList()) }
    var schedules by remember { mutableStateOf<List<Schedule>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scripts = container.scriptRepository.listAsync()
        schedules = container.scheduleRepository.listAsync()
    }

    fun refresh() {
        scope.launch { schedules = container.scheduleRepository.listAsync() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("定时任务") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建定时任务")
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (schedules.isEmpty()) {
                EmptyState("还没有定时任务。点击右下角新建，可设置每日固定时间或按间隔重复运行脚本。")
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(schedules, key = { it.id }) { schedule ->
                        ListItem(
                            headlineContent = { Text(schedule.scriptName) },
                            supportingContent = { Text(describe(schedule)) },
                            trailingContent = {
                                IconButton(onClick = {
                                    scope.launch {
                                        container.scheduleManager.cancelAsync(schedule.id)
                                        refresh()
                                    }
                                }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddScheduleDialog(
            scripts = scripts,
            onDismiss = { showAdd = false },
            onConfirm = { script, hour, minute, interval ->
                val schedule = Schedule(
                    id = UUID.randomUUID().toString(),
                    scriptId = script.id,
                    scriptName = script.name,
                    triggerAtMillis = computeNextTrigger(hour, minute),
                    intervalMinutes = interval,
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
    onConfirm: (ScriptInfo, Int, Int, Long) -> Unit,
) {
    var selected by remember { mutableStateOf(scripts.firstOrNull()) }
    var scriptExpanded by remember { mutableStateOf(false) }
    var hour by remember { mutableStateOf("8") }
    var minute by remember { mutableStateOf("0") }
    var interval by remember { mutableStateOf("0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建定时任务") },
        text = {
            Column {
                Box {
                    OutlinedButton(
                        onClick = { scriptExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(selected?.name ?: "选择脚本")
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
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = hour,
                        onValueChange = { hour = it.filter(Char::isDigit).take(2) },
                        label = { Text("时") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = minute,
                        onValueChange = { minute = it.filter(Char::isDigit).take(2) },
                        label = { Text("分") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = interval,
                    onValueChange = { interval = it.filter(Char::isDigit).take(5) },
                    label = { Text("重复间隔（分钟，0 = 仅一次）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val script = selected ?: return@TextButton
                    val h = hour.toIntOrNull()?.coerceIn(0, 23) ?: 8
                    val m = minute.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    val i = interval.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    onConfirm(script, h, m, i)
                },
                enabled = selected != null,
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private val DESCRIBE_FORMATTER = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

private fun describe(schedule: Schedule): String {
    val time = DESCRIBE_FORMATTER.format(Date(schedule.triggerAtMillis))
    return if (schedule.intervalMinutes > 0) {
        "下次 $time · 每 ${schedule.intervalMinutes} 分钟"
    } else {
        "仅一次 · $time"
    }
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
