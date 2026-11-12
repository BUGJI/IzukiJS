package com.benton.izukijs.ui.flow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.benton.izukijs.R
import com.benton.izukijs.flow.FlowOrder
import com.benton.izukijs.flow.FlowStep
import com.benton.izukijs.flow.ScriptFlow
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.schedule.ScheduleKind
import com.benton.izukijs.ui.automation.TriggerEditor
import com.benton.izukijs.ui.automation.TriggerEditorState
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.ConfirmDialog
import com.benton.izukijs.ui.common.EmptyState
import com.benton.izukijs.ui.common.IzukiPageScaffold
import com.benton.izukijs.ui.common.PageColumn
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SectionGap
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.SwitchRow
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowEditScreen(
    flowId: String?,
    onBack: () -> Unit,
) {
    val container = rememberAppContainer()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = LocalSnackbarController.current
    var scripts by remember { mutableStateOf<List<ScriptInfo>>(emptyList()) }
    var flow by remember { mutableStateOf<ScriptFlow?>(null) }
    var triggerState by remember { mutableStateOf<TriggerEditorState?>(null) }
    var name by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf<List<FlowStep>>(emptyList()) }
    var order by remember { mutableStateOf(FlowOrder.SEQUENTIAL) }
    var continueOnError by remember { mutableStateOf(true) }
    var enabled by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }
    var showScriptPicker by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var baselineKey by remember { mutableStateOf<String?>(null) }
    var showDiscard by remember { mutableStateOf(false) }

    fun draftKey(): String {
        val trigger = triggerState
        val triggerKey = trigger?.let {
            listOf(
                it.kind, it.hour, it.minute, it.interval, it.cron, it.event,
                it.conditionCharging, it.conditionWifi, it.conditionUnlocked,
                it.foregroundPackage,
            ).joinToString(",", prefix = "[", postfix = "]")
        }.orEmpty()
        return listOf(
            name,
            steps.joinToString(";") { "${it.id}:${it.scriptId}" },
            order,
            continueOnError,
            enabled,
            triggerKey,
        ).joinToString("|")
    }

    LaunchedEffect(flowId) {
        scripts = container.scriptRepository.listAsync()
        if (flowId == null) {
            triggerState = TriggerEditorState.empty()
        } else {
            val existing = container.flowRepository.getAsync(flowId)
            if (existing == null) {
                notFound = true
            } else {
                flow = existing
                name = existing.name
                steps = existing.steps
                order = existing.order
                continueOnError = existing.continueOnError
                enabled = existing.enabled
                triggerState = TriggerEditorState.from(existing)
            }
        }
        baselineKey = draftKey()
        loaded = true
    }

    fun moveStep(index: Int, delta: Int) {
        val target = index + delta
        if (target !in steps.indices) return
        steps = steps.toMutableList().apply { add(target, removeAt(index)) }
    }

    val trigger = triggerState
    val canSave = loaded && !notFound && trigger != null &&
        name.isNotBlank() && steps.isNotEmpty() && trigger.cronValid
    val dirty = baselineKey != null && draftKey() != baselineKey

    fun requestBack() {
        if (saving) return
        if (dirty) showDiscard = true else onBack()
    }

    BackHandler { requestBack() }

    fun save() {
        val trigger = triggerState ?: return
        if (!canSave || saving) return
        saving = true
        scope.launch {
            try {
                val base = flow ?: ScriptFlow(
                    id = UUID.randomUUID().toString(),
                    name = name.trim(),
                )
                val saved = base.copy(
                    name = name.trim(),
                    kind = trigger.kind,
                    triggerAtMillis = trigger.triggerAtMillis(),
                    intervalMinutes = if (trigger.kind == ScheduleKind.INTERVAL) {
                        trigger.intervalLong()
                    } else {
                        0L
                    },
                    cron = trigger.cron.trim(),
                    event = trigger.eventOrNull(),
                    conditions = trigger.conditions(),
                    steps = steps,
                    order = order,
                    continueOnError = continueOnError,
                    enabled = enabled,
                )
                // 编辑时 id 不变，scheduleAsync 会按 id 覆盖旧编排并重设 alarm；
                // 不再先 cancelAsync，避免中间态只删不建。
                container.flowManager.scheduleAsync(saved)
                onBack()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                snackbar.show(context.getString(R.string.editor_save_failed))
            } finally {
                saving = false
            }
        }
    }

    IzukiPageScaffold(
        title = {
            Text(
                stringResource(
                    if (flowId == null) R.string.flow_new else R.string.flow_edit_title,
                ),
            )
        },
        onBack = { requestBack() },
        actions = {
            TextButton(onClick = { save() }, enabled = canSave && !saving) {
                Text(stringResource(R.string.flow_save))
            }
        },
    ) { padding ->
        when {
            !loaded -> Unit
            notFound -> EmptyState(
                text = stringResource(R.string.flow_not_found),
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            trigger != null -> PageColumn(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(SectionGap),
            ) {
                SectionCard(title = stringResource(R.string.flow_name)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.flow_name_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SectionCard(title = stringResource(R.string.flow_trigger)) {
                    TriggerEditor(trigger)
                }

                SectionCard(
                    title = stringResource(R.string.flow_steps),
                    trailing = {
                        Box {
                            TextButton(onClick = { showScriptPicker = true }) {
                                Text(stringResource(R.string.flow_add_step))
                            }
                            DropdownMenu(
                                expanded = showScriptPicker,
                                onDismissRequest = { showScriptPicker = false },
                            ) {
                                if (scripts.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.flow_no_scripts)) },
                                        onClick = { showScriptPicker = false },
                                        enabled = false,
                                    )
                                } else {
                                    scripts.forEach { script ->
                                        DropdownMenuItem(
                                            text = { Text(script.name) },
                                            onClick = {
                                                steps = steps + FlowStep(
                                                    id = UUID.randomUUID().toString(),
                                                    scriptId = script.id,
                                                    scriptName = script.name,
                                                )
                                                showScriptPicker = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    },
                ) {
                    if (steps.isEmpty()) {
                        Text(stringResource(R.string.flow_step_empty))
                    } else {
                        steps.forEachIndexed { index, step ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${index + 1}.",
                                    modifier = Modifier.width(28.dp),
                                )
                                Text(
                                    step.scriptName,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { moveStep(index, -1) },
                                    enabled = index > 0,
                                ) {
                                    Icon(
                                        Icons.Filled.KeyboardArrowUp,
                                        contentDescription = stringResource(R.string.flow_move_up),
                                    )
                                }
                                IconButton(
                                    onClick = { moveStep(index, 1) },
                                    enabled = index < steps.lastIndex,
                                ) {
                                    Icon(
                                        Icons.Filled.KeyboardArrowDown,
                                        contentDescription = stringResource(R.string.flow_move_down),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        steps = steps.filterNot { it.id == step.id }
                                    },
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.flow_remove_step),
                                    )
                                }
                            }
                            if (index < steps.lastIndex) HorizontalDivider()
                        }
                    }
                }

                SectionCard(title = stringResource(R.string.flow_order)) {
                    ChipFlow {
                        FilterChip(
                            selected = order == FlowOrder.SEQUENTIAL,
                            onClick = { order = FlowOrder.SEQUENTIAL },
                            label = { Text(stringResource(R.string.flow_order_sequential)) },
                        )
                        FilterChip(
                            selected = order == FlowOrder.RANDOM,
                            onClick = { order = FlowOrder.RANDOM },
                            label = { Text(stringResource(R.string.flow_order_random)) },
                        )
                    }
                }

                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SwitchRow(
                            label = stringResource(R.string.flow_continue_on_error),
                            description = stringResource(R.string.flow_continue_on_error_desc),
                            checked = continueOnError,
                            onChange = { continueOnError = it },
                        )
                        SwitchRow(
                            label = stringResource(R.string.flow_enabled),
                            checked = enabled,
                            onChange = { enabled = it },
                        )
                    }
                }
            }
        }
    }

    if (showDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.script_edit_discard_title),
            message = stringResource(R.string.script_edit_discard_message),
            confirmLabel = stringResource(R.string.script_edit_discard),
            destructive = true,
            onConfirm = {
                showDiscard = false
                onBack()
            },
            onDismiss = { showDiscard = false },
        )
    }
}
