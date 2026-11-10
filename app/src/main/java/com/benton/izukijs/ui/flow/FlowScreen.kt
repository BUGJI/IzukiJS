package com.benton.izukijs.ui.flow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.flow.FlowOrder
import com.benton.izukijs.flow.ScriptFlow
import com.benton.izukijs.schedule.ScheduleEvent
import com.benton.izukijs.schedule.ScheduleKind
import com.benton.izukijs.ui.automation.scheduleEventLabel
import com.benton.izukijs.ui.common.ConfirmDialog
import com.benton.izukijs.ui.common.EmptyState
import com.benton.izukijs.ui.common.IzukiPageScaffold
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.PageMaxWidth
import com.benton.izukijs.ui.common.formatMonthDayTime
import com.benton.izukijs.ui.common.rememberAppHaptics
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowScreen(
    onBack: () -> Unit,
    onOpenFlowEdit: (String?) -> Unit,
) {
    val container = rememberAppContainer()
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    val snackbar = LocalSnackbarController.current
    val resources = LocalResources.current
    val revision by container.flowRepository.revision.collectAsStateWithLifecycle()
    var flows by remember { mutableStateOf<List<ScriptFlow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ScriptFlow?>(null) }

    LaunchedEffect(revision) {
        flows = container.flowRepository.listAsync()
        loaded = true
    }

    IzukiPageScaffold(
        title = { Text(stringResource(R.string.flow_title)) },
        onBack = onBack,
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpenFlowEdit(null) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.flow_new))
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
                    !loaded -> Unit
                    flows.isEmpty() -> EmptyState(
                        text = stringResource(R.string.flow_empty),
                        icon = Icons.Filled.Add,
                        actionLabel = stringResource(R.string.flow_new),
                        onAction = { onOpenFlowEdit(null) },
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 88.dp),
                    ) {
                        items(flows, key = { it.id }) { flow ->
                            ListItem(
                                headlineContent = { Text(flow.name) },
                                supportingContent = { Text(describeFlow(flow)) },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                container.flowRunner.run(flow, manual = true)
                                                scope.launch {
                                                    snackbar.show(
                                                        resources.getString(
                                                            R.string.flow_started,
                                                            flow.name,
                                                        ),
                                                    )
                                                }
                                            },
                                        ) {
                                            Icon(
                                                Icons.Filled.PlayArrow,
                                                contentDescription = stringResource(R.string.flow_run_now),
                                            )
                                        }
                                        IconButton(onClick = { deleteTarget = flow }) {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = stringResource(R.string.common_delete),
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenFlowEdit(flow.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.flow_delete_title),
            message = stringResource(R.string.flow_delete_confirm, target.name),
            confirmLabel = stringResource(R.string.common_delete),
            destructive = true,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                val id = target.id
                haptics.reject()
                deleteTarget = null
                scope.launch {
                    container.flowManager.cancelAsync(id)
                }
            },
        )
    }
}

@Composable
private fun describeFlow(flow: ScriptFlow): String {
    val time = formatMonthDayTime(flow.triggerAtMillis)
    val trigger = when (flow.kind) {
        ScheduleKind.TIME -> stringResource(R.string.flow_next_once, time)
        ScheduleKind.INTERVAL ->
            stringResource(R.string.flow_next_interval, time, flow.intervalMinutes)

        ScheduleKind.CRON -> stringResource(R.string.flow_next_cron, flow.cron, time)
        ScheduleKind.EVENT -> stringResource(
            R.string.flow_event_desc,
            scheduleEventLabel(flow.event ?: ScheduleEvent.CHARGING),
        )
    }
    val conditionParts = buildList {
        if (flow.conditions.charging) add(stringResource(R.string.schedule_condition_charging))
        if (flow.conditions.wifi) add(stringResource(R.string.schedule_condition_wifi))
        if (flow.conditions.unlocked) add(stringResource(R.string.schedule_condition_unlocked))
        if (flow.conditions.foregroundPackage.isNotBlank()) {
            add(
                stringResource(
                    R.string.schedule_foreground_condition,
                    flow.conditions.foregroundPackage,
                ),
            )
        }
    }
    val conditions = if (conditionParts.isEmpty()) {
        ""
    } else {
        stringResource(R.string.schedule_conditions_suffix, conditionParts.joinToString(" / "))
    }
    val order = stringResource(
        when (flow.order) {
            FlowOrder.SEQUENTIAL -> R.string.flow_order_sequential
            FlowOrder.RANDOM -> R.string.flow_order_random
        },
    )
    val steps = stringResource(R.string.flow_step_count, flow.steps.size)
    val enabled = if (flow.enabled) "" else " · " + stringResource(R.string.flow_disabled)
    return "$steps · $order · $trigger$conditions$enabled"
}
