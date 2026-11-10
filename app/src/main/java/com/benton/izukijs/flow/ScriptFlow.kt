package com.benton.izukijs.flow

import com.benton.izukijs.schedule.ScheduleConditions
import com.benton.izukijs.schedule.ScheduleEvent
import com.benton.izukijs.schedule.ScheduleKind

/** 编排内步骤的执行顺序。随机顺序只在单次触发时打乱，不改变持久化的配置顺序。 */
enum class FlowOrder { SEQUENTIAL, RANDOM }

/** 编排中的一个脚本步骤。 */
data class FlowStep(
    val id: String,
    val scriptId: String,
    val scriptName: String,
)

/**
 * 脚本编排：复用定时任务的触发方式，触发后串行执行一组脚本。
 *
 * [continueOnError] 为 true 时，某一步失败仍继续执行后续步骤；为 false 时立即停止。
 */
data class ScriptFlow(
    val id: String,
    val name: String,
    val kind: ScheduleKind = ScheduleKind.TIME,
    val triggerAtMillis: Long = 0L,
    val intervalMinutes: Long = 0L,
    val cron: String = "",
    val event: ScheduleEvent? = null,
    val conditions: ScheduleConditions = ScheduleConditions(),
    val steps: List<FlowStep> = emptyList(),
    val order: FlowOrder = FlowOrder.SEQUENTIAL,
    val continueOnError: Boolean = true,
    val enabled: Boolean = true,
    val lastTriggeredAtMillis: Long = 0L,
)
