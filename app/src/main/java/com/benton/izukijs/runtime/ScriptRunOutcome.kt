package com.benton.izukijs.runtime

import com.benton.izukijs.core.log.RunStatus

/** 一次脚本运行的结束信息，用于区分正常完成、脚本主动退出和用户停止。 */
data class RunCompletion(
    val success: Boolean,
    val stopped: Boolean,
    val status: RunStatus,
)

/** 一次脚本运行等待完成后的结果。 */
sealed interface ScriptRunOutcome {

    /** 已有脚本在运行，本次未启动。 */
    data object Busy : ScriptRunOutcome

    /** 脚本已结束；[success] 与现有 onFinished 回调语义一致。 */
    data class Finished(val success: Boolean) : ScriptRunOutcome

    /** 用户/外部主动停止，调用方（如脚本编排）应终止后续步骤。 */
    data object Stopped : ScriptRunOutcome
}
