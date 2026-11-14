package com.benton.izukijs.schedule

/** 定时任务持久化中执行器需要的最小读写面。 */
interface ScheduleStore {
    fun list(): List<Schedule>

    fun upsert(schedule: Schedule)
}

/** 定时任务执行结果回调；生产实现为 [ScheduleManager]。 */
interface ScheduleLifecycle {
    fun onFinished(schedule: Schedule, success: Boolean)

    fun onSkipped(schedule: Schedule)

    fun cancel(id: String)
}
