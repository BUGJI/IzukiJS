package com.benton.izukijs.schedule

data class Schedule(
    val id: String,
    val scriptId: String,
    val scriptName: String,
    val triggerAtMillis: Long,
    /** 重复间隔（分钟）；0 表示仅执行一次。 */
    val intervalMinutes: Long,
)
