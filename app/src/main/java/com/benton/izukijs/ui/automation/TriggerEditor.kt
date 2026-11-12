package com.benton.izukijs.ui.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.benton.izukijs.R
import com.benton.izukijs.flow.ScriptFlow
import com.benton.izukijs.schedule.CronExpression
import com.benton.izukijs.schedule.Schedule
import com.benton.izukijs.schedule.ScheduleConditions
import com.benton.izukijs.schedule.ScheduleEvent
import com.benton.izukijs.schedule.ScheduleKind
import com.benton.izukijs.ui.common.ChipFlow
import java.util.Calendar

/**
 * 定时任务与脚本编排共用的触发配置编辑状态。
 * 时间 / 间隔等字段保留字符串，避免输入中间态被外部值回弹。
 */
@Stable
class TriggerEditorState private constructor(
    initialHour: String,
    initialMinute: String,
    initialIntervalMinutes: Long,
    initialCron: String,
    initialEvent: ScheduleEvent?,
    initialConditions: ScheduleConditions,
    private val initialKind: ScheduleKind,
    private val initialTriggerAtMillis: Long,
    private val initialHourValue: Int,
    private val initialMinuteValue: Int,
    private val initialIntervalValue: Long,
) {

    var kind by mutableStateOf(initialKind)
    var hour by mutableStateOf(initialHour)
    var minute by mutableStateOf(initialMinute)
    var interval by mutableStateOf(
        initialIntervalMinutes.takeIf { it > 0L }?.toString() ?: "60",
    )
    var cron by mutableStateOf(initialCron.ifBlank { "0 8 * * *" })
    var event by mutableStateOf(initialEvent ?: ScheduleEvent.CHARGING)
    var conditionCharging by mutableStateOf(initialConditions.charging)
    var conditionWifi by mutableStateOf(initialConditions.wifi)
    var conditionUnlocked by mutableStateOf(initialConditions.unlocked)
    var foregroundPackage by mutableStateOf(initialConditions.foregroundPackage)

    val cronValid: Boolean
        get() = kind != ScheduleKind.CRON || CronExpression.parse(cron) != null

    fun hourInt(): Int = hour.toIntOrNull()?.coerceIn(0, 23) ?: 8

    fun minuteInt(): Int = minute.toIntOrNull()?.coerceIn(0, 59) ?: 0

    fun intervalLong(): Long = interval.toLongOrNull()?.coerceAtLeast(1L) ?: 60L

    fun conditions(): ScheduleConditions = ScheduleConditions(
        charging = conditionCharging,
        wifi = conditionWifi,
        unlocked = conditionUnlocked,
        foregroundPackage = foregroundPackage.trim(),
    )

    fun eventOrNull(): ScheduleEvent? = if (kind == ScheduleKind.EVENT) event else null

    fun nextTriggerMillis(): Long = computeNextTrigger(hourInt(), minuteInt())

    /**
     * 计算保存后的下次触发时间。
     *
     * 编辑一个已存在的任务时，如果触发方式与时间字段没有改动，保留原有触发时刻
     * （interval 任务通常带秒级偏移），避免仅改名称就把下次运行推迟到明天。
     * Cron 由调度器保存时重新计算，这里返回 0。
     */
    fun triggerAtMillis(now: Long = System.currentTimeMillis()): Long = when (kind) {
        ScheduleKind.EVENT -> 0L
        ScheduleKind.CRON -> 0L
        ScheduleKind.INTERVAL -> {
            val unchanged = initialKind == ScheduleKind.INTERVAL &&
                intervalLong() == initialIntervalValue
            if (unchanged && initialTriggerAtMillis > now) {
                initialTriggerAtMillis
            } else {
                now + intervalLong().coerceAtLeast(1L) * 60_000L
            }
        }
        ScheduleKind.TIME -> {
            val unchanged = initialKind == ScheduleKind.TIME &&
                hourInt() == initialHourValue &&
                minuteInt() == initialMinuteValue
            if (unchanged && initialTriggerAtMillis > now) {
                initialTriggerAtMillis
            } else {
                computeNextTrigger(hourInt(), minuteInt())
            }
        }
    }

    companion object {

        fun empty(): TriggerEditorState = TriggerEditorState(
            initialKind = ScheduleKind.TIME,
            initialHour = "8",
            initialMinute = "0",
            initialIntervalMinutes = 60L,
            initialCron = "0 8 * * *",
            initialEvent = ScheduleEvent.CHARGING,
            initialConditions = ScheduleConditions(),
            initialTriggerAtMillis = 0L,
            initialHourValue = 8,
            initialMinuteValue = 0,
            initialIntervalValue = 60L,
        )

        fun from(schedule: Schedule): TriggerEditorState {
            val calendar = schedule.triggerAtMillis
                .takeIf { it > 0L }
                ?.let { Calendar.getInstance().apply { timeInMillis = it } }
            return TriggerEditorState(
                initialKind = schedule.kind,
                initialHour = calendar?.get(Calendar.HOUR_OF_DAY)?.toString() ?: "8",
                initialMinute = calendar?.get(Calendar.MINUTE)?.toString() ?: "0",
                initialIntervalMinutes = schedule.intervalMinutes,
                initialCron = schedule.cron,
                initialEvent = schedule.event,
                initialConditions = schedule.conditions,
                initialTriggerAtMillis = schedule.triggerAtMillis,
                initialHourValue = calendar?.get(Calendar.HOUR_OF_DAY) ?: 8,
                initialMinuteValue = calendar?.get(Calendar.MINUTE) ?: 0,
                initialIntervalValue = schedule.intervalMinutes,
            )
        }

        fun from(flow: ScriptFlow): TriggerEditorState {
            val calendar = flow.triggerAtMillis
                .takeIf { it > 0L }
                ?.let { Calendar.getInstance().apply { timeInMillis = it } }
            return TriggerEditorState(
                initialKind = flow.kind,
                initialHour = calendar?.get(Calendar.HOUR_OF_DAY)?.toString() ?: "8",
                initialMinute = calendar?.get(Calendar.MINUTE)?.toString() ?: "0",
                initialIntervalMinutes = flow.intervalMinutes,
                initialCron = flow.cron,
                initialEvent = flow.event,
                initialConditions = flow.conditions,
                initialTriggerAtMillis = flow.triggerAtMillis,
                initialHourValue = calendar?.get(Calendar.HOUR_OF_DAY) ?: 8,
                initialMinuteValue = calendar?.get(Calendar.MINUTE) ?: 0,
                initialIntervalValue = flow.intervalMinutes,
            )
        }
    }
}

/** 触发方式、时间 / Cron / 事件与附加条件。 */
@Composable
fun TriggerEditor(state: TriggerEditorState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChipFlow {
            ScheduleKind.entries.forEach { item ->
                FilterChip(
                    selected = state.kind == item,
                    onClick = { state.kind = item },
                    label = { Text(scheduleKindLabel(item)) },
                )
            }
        }

        when (state.kind) {
            ScheduleKind.TIME, ScheduleKind.INTERVAL -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.hour,
                        onValueChange = { state.hour = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.schedule_hour)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = state.minute,
                        onValueChange = { state.minute = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.schedule_minute)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (state.kind == ScheduleKind.INTERVAL) {
                    OutlinedTextField(
                        value = state.interval,
                        onValueChange = { state.interval = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.schedule_interval)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            ScheduleKind.CRON -> {
                OutlinedTextField(
                    value = state.cron,
                    onValueChange = { state.cron = it },
                    label = { Text(stringResource(R.string.schedule_cron_label)) },
                    placeholder = { Text(stringResource(R.string.schedule_cron_hint)) },
                    singleLine = true,
                    isError = state.cron.isNotBlank() && !state.cronValid,
                    supportingText = {
                        Text(
                            if (state.cronValid) {
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
                            selected = state.event == item,
                            onClick = { state.event = item },
                            label = { Text(scheduleEventLabel(item)) },
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
                selected = state.conditionCharging,
                onClick = { state.conditionCharging = !state.conditionCharging },
                label = { Text(stringResource(R.string.schedule_condition_charging)) },
            )
            FilterChip(
                selected = state.conditionWifi,
                onClick = { state.conditionWifi = !state.conditionWifi },
                label = { Text(stringResource(R.string.schedule_condition_wifi)) },
            )
            FilterChip(
                selected = state.conditionUnlocked,
                onClick = { state.conditionUnlocked = !state.conditionUnlocked },
                label = { Text(stringResource(R.string.schedule_condition_unlocked)) },
            )
        }
        OutlinedTextField(
            value = state.foregroundPackage,
            onValueChange = { state.foregroundPackage = it.trim() },
            label = { Text(stringResource(R.string.schedule_foreground_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun scheduleKindLabel(kind: ScheduleKind): String = stringResource(
    when (kind) {
        ScheduleKind.TIME -> R.string.schedule_kind_time
        ScheduleKind.INTERVAL -> R.string.schedule_kind_interval
        ScheduleKind.CRON -> R.string.schedule_kind_cron
        ScheduleKind.EVENT -> R.string.schedule_kind_event
    },
)

@Composable
fun scheduleEventLabel(event: ScheduleEvent): String = stringResource(
    when (event) {
        ScheduleEvent.CHARGING -> R.string.schedule_event_charging
        ScheduleEvent.WIFI -> R.string.schedule_event_wifi
        ScheduleEvent.UNLOCK -> R.string.schedule_event_unlock
    },
)

/** 根据「时:分」计算下一次触发时间；今天已过则顺延到明天。 */
fun computeNextTrigger(hour: Int, minute: Int): Long {
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
