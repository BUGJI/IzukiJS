package com.benton.izukijs.core.log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class LogBusTest {

    private val logcat = CopyOnWriteArrayList<Pair<LogLevel, String>>()

    private fun bus(
        capacity: Int = 500,
        elapsed: () -> Long = { 0L },
    ) = LogBus(
        capacity = capacity,
        clock = { 1_000L },
        elapsed = elapsed,
        logcat = { level, message -> logcat += level to message },
        emitIntervalMs = 0L,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun awaitEntries(bus: LogBus, size: Int): List<LogEntry> = runBlocking {
        withTimeout(1_000L) { bus.entries.first { it.size >= size } }
    }

    @Test
    fun appendsEntryAndPublishesSnapshot() {
        val bus = bus()

        bus.info("hello")

        val entry = awaitEntries(bus, 1).single()
        assertEquals(1L, entry.id)
        assertEquals(1_000L, entry.timeMillis)
        assertEquals(LogLevel.INFO, entry.level)
        assertEquals("hello", entry.message)
        assertEquals(listOf(LogLevel.INFO to "hello"), logcat.toList())
    }

    @Test
    fun dropsEntriesBelowMinimumLevel() {
        val bus = bus()
        bus.minLevel = LogLevel.WARN

        bus.info("hidden")
        bus.warn("shown")

        assertEquals(listOf(LogLevel.WARN to "shown"), logcat.toList())
        assertEquals("shown", awaitEntries(bus, 1).single().message)
    }

    @Test
    fun keepsOnlyMostRecentCapacity() {
        val bus = bus(capacity = 2)

        bus.info("one")
        bus.info("two")
        bus.error("three")

        val entries = awaitEntries(bus, 2)
        assertEquals(listOf("two", "three"), entries.map { it.message })
        assertEquals(listOf(2L, 3L), entries.map { it.id })
    }

    @Test
    fun sinkReceivesEveryEntry() {
        val bus = bus()
        val received = mutableListOf<LogEntry>()
        bus.setSink { received += it }

        bus.success("done")

        assertEquals("done", received.single().message)
        assertEquals(LogLevel.SUCCESS, received.single().level)
    }

    @Test
    fun clearEmptiesBufferAndPublishesEmpty() {
        val bus = bus()
        bus.info("before")
        awaitEntries(bus, 1)

        bus.clear()

        assertTrue(bus.entries.value.isEmpty())
        bus.info("after")
        assertEquals("after", awaitEntries(bus, 1).single().message)
    }

    @Test
    fun debugThrottledSamplesPerKeyAndInterval() {
        var now = 0L
        val bus = bus(elapsed = { now })

        bus.debugThrottled("key", "first", intervalMs = 500L)
        bus.debugThrottled("key", "second", intervalMs = 500L)
        assertEquals(1, awaitEntries(bus, 1).size)

        now = 499L
        bus.debugThrottled("key", "still-throttled", intervalMs = 500L)
        assertEquals(1, bus.entries.value.size)

        now = 500L
        bus.debugThrottled("key", "allowed", intervalMs = 500L)
        assertEquals(2, awaitEntries(bus, 2).size)

        bus.debugThrottled("other", "other-key", intervalMs = 500L)
        assertEquals(3, awaitEntries(bus, 3).size)
    }

    @Test
    fun debugThrottledDropsWhenDebugDisabled() {
        val bus = bus()
        bus.minLevel = LogLevel.INFO

        bus.debugThrottled("key", "hidden")

        assertTrue(bus.entries.value.isEmpty())
        assertTrue(logcat.isEmpty())
    }

    @Test
    fun clearResetsThrottleTimestamps() {
        var now = 0L
        val bus = bus(elapsed = { now })

        bus.debugThrottled("key", "first", intervalMs = 500L)
        awaitEntries(bus, 1)
        bus.clear()

        bus.debugThrottled("key", "after-clear", intervalMs = 500L)

        assertEquals("after-clear", awaitEntries(bus, 1).single().message)
    }
}
