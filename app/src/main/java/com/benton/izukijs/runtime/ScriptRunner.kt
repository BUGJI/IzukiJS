package com.benton.izukijs.runtime

import kotlinx.coroutines.flow.StateFlow

/**
 * 编排 / 定时执行器依赖的脚本运行入口。
 *
 * 只暴露调度所需的「是否在跑 / 请求停止 / 启动并等待结束」，
 * 避免执行器反向依赖 [ScriptExecutionManager] 的全部配置与生命周期。
 */
interface ScriptRunner {
    val running: StateFlow<Boolean>

    fun requestStop()

    /**
     * 启动一次调度触发的脚本；[onFinished] 与现有回调语义一致。
     * 返回 false 表示已有脚本在运行。
     */
    fun runScheduled(
        scriptId: String,
        scriptName: String,
        source: String,
        trigger: RunTrigger,
        onFinished: (Boolean) -> Unit,
    ): Boolean

    /** 启动脚本并等待其真正结束，供编排「一步结束再启动下一步」。 */
    suspend fun runToCompletion(
        scriptId: String,
        scriptName: String,
        source: String,
        trigger: RunTrigger,
    ): ScriptRunOutcome
}
