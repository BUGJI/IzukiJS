package com.benton.izukijs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RunGateTest {

    private class Engine(val name: String)

    @Test
    fun startSucceedsOnceUntilReset() {
        val gate = RunGate<Engine>()

        assertTrue(gate.tryStart("a"))
        assertTrue(gate.isRunning())
        assertEquals("a", gate.activeRunId())
        assertFalse(gate.tryStart("b"))

        assertTrue(gate.reset("a"))
        assertFalse(gate.isRunning())
        assertTrue(gate.tryStart("b"))
    }

    @Test
    fun stopBeforeEngineWaitsAndIsDeliveredOnAttach() {
        val gate = RunGate<Engine>()
        gate.tryStart("a")

        assertSame(RunGate.StopAction.WaitStart, gate.resolveStop())
        assertTrue(gate.isStopRequested())

        val engine = Engine("a")
        assertTrue(gate.attachEngine("a", engine))
        assertEquals(
            RunGate.StopAction.StopEngine(engine),
            gate.resolveStop(),
        )
    }

    @Test
    fun attachClearsPendingStopFlagForNextRun() {
        val gate = RunGate<Engine>()
        gate.tryStart("a")
        gate.resolveStop()
        gate.reset("a")

        gate.tryStart("b")

        assertFalse(gate.isStopRequested())
        assertFalse(gate.attachEngine("b", Engine("b")))
    }

    @Test
    fun stopWithEngineReturnsThatEngine() {
        val gate = RunGate<Engine>()
        val engine = Engine("a")
        gate.tryStart("a")
        gate.attachEngine("a", engine)

        val action = gate.resolveStop()

        assertTrue(action is RunGate.StopAction.StopEngine)
        assertSame(engine, (action as RunGate.StopAction.StopEngine).engine)
    }

    @Test
    fun stopWithoutRunDoesNothing() {
        val gate = RunGate<Engine>()

        assertSame(RunGate.StopAction.None, gate.resolveStop())
        assertFalse(gate.isStopRequested())
    }

    @Test
    fun staleDetachDoesNotClearCurrentEngine() {
        val gate = RunGate<Engine>()
        gate.tryStart("a")
        gate.attachEngine("a", Engine("a"))

        assertNull(gate.detachEngine("b"))

        val current = gate.resolveStop()
        assertTrue(current is RunGate.StopAction.StopEngine)
        assertEquals("a", (current as RunGate.StopAction.StopEngine).engine.name)
    }

    @Test
    fun staleResetDoesNotClearNewRun() {
        val gate = RunGate<Engine>()
        gate.tryStart("a")
        gate.attachEngine("a", Engine("a"))
        gate.reset("a")

        gate.tryStart("b")
        val engineB = Engine("b")
        gate.attachEngine("b", engineB)

        assertFalse(gate.reset("a"))
        assertTrue(gate.isRunning())
        assertEquals("b", gate.activeRunId())
        val action = gate.resolveStop()
        assertSame(engineB, (action as RunGate.StopAction.StopEngine).engine)
    }

    @Test
    fun attachingStaleRunIsTreatedAsStoppedAndDoesNotBind() {
        val gate = RunGate<Engine>()
        gate.tryStart("a")
        gate.reset("a")
        gate.tryStart("b")

        assertTrue(gate.attachEngine("a", Engine("a")))
        assertSame(RunGate.StopAction.WaitStart, gate.resolveStop())
    }

    @Test
    fun concurrentStartAllowsExactlyOne() {
        val gate = RunGate<Engine>()
        val threads = 16
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val successes = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) { index ->
                pool.execute {
                    ready.countDown()
                    start.await()
                    if (gate.tryStart("run-$index")) successes.incrementAndGet()
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }

        assertEquals(1, successes.get())
        assertTrue(gate.isRunning())
    }
}
