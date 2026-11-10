package com.benton.izukijs.runtime

/** 一次脚本运行等待完成后的结果。 */
sealed interface ScriptRunOutcome {

    /** 已有脚本在运行，本次未启动。 */
    data object Busy : ScriptRunOutcome

    /** 脚本已结束；[success] 与现有 onFinished 回调语义一致。 */
    data class Finished(val success: Boolean) : ScriptRunOutcome
}
