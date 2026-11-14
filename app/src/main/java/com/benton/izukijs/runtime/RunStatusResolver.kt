package com.benton.izukijs.runtime

import com.benton.izukijs.core.log.RunStatus

/**
 * 把运行结束时的若干标志归一为 [RunStatus] 与成功标记。
 *
 * 注意「脚本主动 exit()」既不是失败也不是成功完成：状态是 [RunStatus.STOPPED]，
 * 但成功标记为 true。这与 `onFinished` 的历史语义保持一致。
 */
internal object RunStatusResolver {

    fun resolve(
        timedOut: Boolean,
        userStopped: Boolean,
        exited: Boolean,
        failed: Boolean,
    ): ResolvedRun = when {
        timedOut -> ResolvedRun(RunStatus.TIMEOUT, success = false)
        userStopped -> ResolvedRun(RunStatus.STOPPED, success = false)
        exited -> ResolvedRun(RunStatus.STOPPED, success = true)
        failed -> ResolvedRun(RunStatus.FAILED, success = false)
        else -> ResolvedRun(RunStatus.SUCCEEDED, success = true)
    }

    data class ResolvedRun(val status: RunStatus, val success: Boolean)
}
