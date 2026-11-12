package com.benton.izukijs.controller.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RecordingSuppressionTest {

    @Test
    fun suppressionOnlyAppliesToCurrentThread() {
        assertFalse(RecordingSuppression.isSuppressed())
        val started = CountDownLatch(1)
        val checked = CountDownLatch(1)
        var suppressedInOtherThread = false

        RecordingSuppression.withoutRecording {
            assertTrue(RecordingSuppression.isSuppressed())
            val thread = Thread({
                // 其他线程（例如并发脚本的 Shell 调用）不应被当前录制的内部命令抑制。
                suppressedInOtherThread = RecordingSuppression.isSuppressed()
                started.countDown()
                checked.countDown()
            }, "suppression-probe")
            thread.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
        }

        assertTrue(checked.await(2, TimeUnit.SECONDS))
        assertFalse(suppressedInOtherThread)
        assertFalse(RecordingSuppression.isSuppressed())
    }

    @Test
    fun nestedScopesKeepOuterSuppressionUntilExit() {
        RecordingSuppression.withoutRecording {
            RecordingSuppression.withoutRecording {
                assertTrue(RecordingSuppression.isSuppressed())
            }
            assertTrue(RecordingSuppression.isSuppressed())
        }
        assertFalse(RecordingSuppression.isSuppressed())
    }

    @Test
    fun blockResultAndExceptionsRestoreState() {
        assertEquals("ok", RecordingSuppression.withoutRecording { "ok" })
        runCatching { RecordingSuppression.withoutRecording<Unit> { error("boom") } }
        assertFalse(RecordingSuppression.isSuppressed())
    }
}
