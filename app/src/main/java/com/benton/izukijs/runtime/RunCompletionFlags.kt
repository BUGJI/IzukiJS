package com.benton.izukijs.runtime

/**
 * 一次运行结束时用于判定状态与成功标记的标志。
 *
 * 抽成纯函数有两个目的：
 * - 强制完成判定只读本 run 的 session 状态，不再读共享运行位（旧 run 可能已被新 run 取代）；
 * - 让「被顶掉 / 用户停止 / 超时 / 主动 exit」等组合可以被单元测试覆盖。
 */
internal data class RunCompletionFlags(
    val timedOut: Boolean,
    val userStopped: Boolean,
    val exited: Boolean,
)

internal fun resolveRunCompletionFlags(
    abandoned: Boolean,
    stopRequested: Boolean,
    engineTimedOut: Boolean,
    engineExitRequested: Boolean,
    failureIsExit: Boolean,
): RunCompletionFlags {
    val userStopped = abandoned || stopRequested
    return RunCompletionFlags(
        // 被顶掉的旧 run 即使引擎恰好超时，也不应把结果记成超时：它已不再拥有完成权，
        // 否则用户会看到一条误导性的「脚本超时」通知。
        timedOut = !abandoned && engineTimedOut,
        userStopped = userStopped,
        exited = !userStopped && (engineExitRequested || failureIsExit),
    )
}
