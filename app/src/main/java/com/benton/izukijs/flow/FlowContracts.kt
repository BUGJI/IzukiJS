package com.benton.izukijs.flow

/** 编排持久化中执行器需要的最小读写面。 */
interface FlowStore {
    fun list(): List<ScriptFlow>

    fun upsert(flow: ScriptFlow)
}

/** 编排结束后的调度回调；生产实现为 [FlowManager]。 */
interface FlowScheduler {
    fun onFinished(flow: ScriptFlow, success: Boolean)

    fun onSkipped(flow: ScriptFlow)
}

/** 编排结果通知；生产实现基于 `NotificationDispatcher`。 */
interface FlowNotifier {
    fun summary(
        flow: ScriptFlow,
        executed: Int,
        succeeded: Int,
        total: Int,
        allSucceeded: Boolean,
        durationMs: Long,
    )
}
