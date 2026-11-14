package com.benton.izukijs.runtime

import com.benton.izukijs.core.log.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunStatusResolverTest {

    @Test
    fun timeoutWinsOverEverything() {
        val result = RunStatusResolver.resolve(
            timedOut = true,
            userStopped = true,
            exited = true,
            failed = true,
        )

        assertEquals(RunStatus.TIMEOUT, result.status)
        assertFalse(result.success)
    }

    @Test
    fun userStopIsStoppedAndNotSuccessful() {
        val result = RunStatusResolver.resolve(
            timedOut = false,
            userStopped = true,
            exited = true,
            failed = true,
        )

        assertEquals(RunStatus.STOPPED, result.status)
        assertFalse(result.success)
    }

    @Test
    fun scriptExitIsStoppedButSuccessful() {
        val result = RunStatusResolver.resolve(
            timedOut = false,
            userStopped = false,
            exited = true,
            failed = true,
        )

        assertEquals(RunStatus.STOPPED, result.status)
        assertTrue(result.success)
    }

    @Test
    fun failureIsFailed() {
        val result = RunStatusResolver.resolve(
            timedOut = false,
            userStopped = false,
            exited = false,
            failed = true,
        )

        assertEquals(RunStatus.FAILED, result.status)
        assertFalse(result.success)
    }

    @Test
    fun normalCompletionSucceeds() {
        val result = RunStatusResolver.resolve(
            timedOut = false,
            userStopped = false,
            exited = false,
            failed = false,
        )

        assertEquals(RunStatus.SUCCEEDED, result.status)
        assertTrue(result.success)
    }
}
