package com.benton.izukijs.runtime

/** 一次启动请求的处理结果；[Rejected] 之外都表示请求已被接受。 */
sealed interface RunStartResult {

    /** 已立即启动。 */
    data object Started : RunStartResult

    /** 已进入单槽队列，等当前脚本结束后启动。 */
    data object Queued : RunStartResult

    /** 已请求终止当前脚本，完成后启动本次请求。 */
    data class Terminating(val currentScript: String?) : RunStartResult

    /** 按当前策略拒绝。 */
    data class Rejected(val currentScript: String?) : RunStartResult

    val accepted: Boolean
        get() = this !is Rejected
}
