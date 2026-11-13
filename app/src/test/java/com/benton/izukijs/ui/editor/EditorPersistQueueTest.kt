package com.benton.izukijs.ui.editor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPersistQueueTest {

    @Test
    fun serializesWritesInSubmissionOrder() = runBlocking {
        val queue = EditorPersistQueue()
        val order = mutableListOf<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = async {
            queue.run("script") {
                firstStarted.complete(Unit)
                releaseFirst.await()
                order += "first"
                PersistOutcome.Saved("first")
            }
        }
        firstStarted.await()

        val second = async {
            queue.run("script") {
                order += "second"
                PersistOutcome.Saved("second")
            }
        }

        releaseFirst.complete(Unit)
        assertEquals(PersistOutcome.Saved("first"), first.await())
        assertEquals(PersistOutcome.Saved("second"), second.await())
        assertEquals(listOf("first", "second"), order)
    }

    @Test
    fun convertsWriteExceptionToFailedOutcome() = runBlocking {
        val queue = EditorPersistQueue()
        val outcome = queue.run("script") {
            throw IllegalStateException("disk full")
        }

        assertTrue(outcome is PersistOutcome.Failed)
        assertEquals("disk full", (outcome as PersistOutcome.Failed).message)
    }

    @Test
    fun differentScriptsDoNotShareVersionState() = runBlocking {
        val queue = EditorPersistQueue()
        val first = queue.run("a") { PersistOutcome.Saved("a") }
        val second = queue.run("b") { PersistOutcome.Saved("b") }

        assertEquals(PersistOutcome.Saved("a"), first)
        assertEquals(PersistOutcome.Saved("b"), second)
    }
}
