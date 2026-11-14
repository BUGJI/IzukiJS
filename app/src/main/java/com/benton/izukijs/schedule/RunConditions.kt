package com.benton.izukijs.schedule

/**
 * 运行前条件检查接口；生产实现为 [TriggerConditions]。
 *
 * 单独抽出后，编排 / 定时执行器可以用假条件做确定性测试，
 * 不依赖电池、Wi-Fi、锁屏等系统服务。
 */
interface RunConditions {
    fun met(conditions: ScheduleConditions): Boolean
}
