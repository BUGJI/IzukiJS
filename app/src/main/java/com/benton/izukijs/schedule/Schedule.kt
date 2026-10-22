package com.benton.izukijs.schedule

/** 触发方式。 */
enum class ScheduleKind { TIME, INTERVAL, CRON, EVENT }

/** 事件触发来源。 */
enum class ScheduleEvent { CHARGING, WIFI, UNLOCK }

/**
 * 运行前的附加条件；全部满足才会执行脚本。
 * [foregroundPackage] 留空表示不限制前台应用。
 */
data class ScheduleConditions(
    val charging: Boolean = false,
    val wifi: Boolean = false,
    val unlocked: Boolean = false,
    val foregroundPackage: String = "",
) {
    val isEmpty: Boolean
        get() = !charging && !wifi && !unlocked && foregroundPackage.isBlank()
}

data class Schedule(
    val id: String,
    val scriptId: String,
    val scriptName: String,
    val triggerAtMillis: Long,
    /** 重复间隔（分钟）；0 表示仅执行一次。 */
    val intervalMinutes: Long,
    val kind: ScheduleKind = if (intervalMinutes > 0) ScheduleKind.INTERVAL else ScheduleKind.TIME,
    val cron: String = "",
    val event: ScheduleEvent? = null,
    val conditions: ScheduleConditions = ScheduleConditions(),
    /** 失败后最多重试次数。 */
    val retryCount: Int = 0,
    /** 两次重试之间的间隔（分钟）。 */
    val retryDelayMinutes: Long = 0,
    /** 当前已重试次数（由调度器维护）。 */
    val retryAttempt: Int = 0,
    /** 上次触发时间，用于事件去抖。 */
    val lastTriggeredAtMillis: Long = 0L,
)
