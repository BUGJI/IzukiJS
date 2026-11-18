package com.benton.izukijs.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunCompletionFlagsTest {

    @Test
    fun abandonedRunIsNeverTreatedAsExitEvenIfEngineExitRequested() {
        val flags = resolveRunCompletionFlags(
            abandoned = true,
            stopRequested = false,
            engineTimedOut = false,
            engineExitRequested = true,
            failureIsExit = true,
        )

        assertTrue(flags.userStopped)
        assertFalse(flags.exited)
    }

    @Test
    fun abandonedRunDoesNotClaimTimeout() {
        val flags = resolveRunCompletionFlags(
            abandoned = true,
            stopRequested = false,
            engineTimedOut = true,
            engineExitRequested = false,
            failureIsExit = false,
        )

        assertFalse(flags.timedOut)
        assertTrue(flags.userStopped)
    }

    @Test
    fun sessionStopRequestSurvivesSharedGateReset() {
        // 新 run 启动后会重置共享 gate 的 stopRequested；旧 run 仍应凭本 session 判定为停止。
        val flags = resolveRunCompletionFlags(
            abandoned = false,
            stopRequested = true,
            engineTimedOut = false,
            engineExitRequested = true,
            failureIsExit = false,
        )

        assertTrue(flags.userStopped)
        assertFalse(flags.exited)
    }

    @Test
    fun scriptExitIsExitedAndSuccessful() {
        val flags = resolveRunCompletionFlags(
            abandoned = false,
            stopRequested = false,
            engineTimedOut = false,
            engineExitRequested = true,
            failureIsExit = false,
        )

        assertFalse(flags.userStopped)
        assertTrue(flags.exited)
    }

    @Test
    fun exitExceptionCountsAsExited() {
        val flags = resolveRunCompletionFlags(
            abandoned = false,
            stopRequested = false,
            engineTimedOut = false,
            engineExitRequested = false,
            failureIsExit = true,
        )

        assertTrue(flags.exited)
    }

    @Test
    fun timeoutWinsWhenRunStillOwnsTheSlot() {
        val flags = resolveRunCompletionFlags(
            abandoned = false,
            stopRequested = true,
            engineTimedOut = true,
            engineExitRequested = true,
            failureIsExit = true,
        )

        assertTrue(flags.timedOut)
        assertTrue(flags.exited.not())
    }
}
