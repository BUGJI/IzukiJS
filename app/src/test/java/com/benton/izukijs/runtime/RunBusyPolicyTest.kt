package com.benton.izukijs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunBusyPolicyTest {

    @Test
    fun fromKeyFallsBackToReject() {
        assertEquals(RunBusyPolicy.QUEUE, RunBusyPolicy.fromKey("queue"))
        assertEquals(RunBusyPolicy.TERMINATE, RunBusyPolicy.fromKey("terminate"))
        assertEquals(RunBusyPolicy.REJECT, RunBusyPolicy.fromKey(null))
        assertEquals(RunBusyPolicy.REJECT, RunBusyPolicy.fromKey("unknown"))
    }

    @Test
    fun onlyRejectedIsNotAccepted() {
        assertTrue(RunStartResult.Started.accepted)
        assertTrue(RunStartResult.Queued.accepted)
        assertTrue(RunStartResult.Terminating("running").accepted)
        assertFalse(RunStartResult.Rejected("running").accepted)
    }
}
