package com.benton.izukijs.mcp.tools

import com.benton.izukijs.testing.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [OperationRegistry] 的生命周期与状态流转测试。 */
class OperationRegistryTest {

    @Test
    fun newOperationIsRunning() {
        val registry = OperationRegistry()
        val id = registry.create("gesture")
        val op = registry.get(id)
        assertNotNull(op)
        assertEquals(OperationStatus.RUNNING, op!!.status)
        assertEquals("gesture", op.tool)
        assertNull(op.finishedAt)
    }

    @Test
    fun succeedMarksDoneWithResult() {
        val registry = OperationRegistry()
        val id = registry.create("ocr")
        registry.succeed(id, McpToolResult.text("done"))
        val op = registry.get(id)!!
        assertEquals(OperationStatus.DONE, op.status)
        assertNotNull(op.result)
        assertNotNull(op.finishedAt)
    }

    @Test
    fun failMarksFailedWithError() {
        val registry = OperationRegistry()
        val id = registry.create("click")
        registry.fail(id, "boom")
        val op = registry.get(id)!!
        assertEquals(OperationStatus.FAILED, op.status)
        assertEquals("boom", op.error)
    }

    @Test
    fun unknownIdReturnsNull() {
        assertNull(OperationRegistry().get("missing"))
    }

    @Test
    fun listIsSortedByStartDescending() {
        val clock = FakeClock(1_000L)
        val registry = OperationRegistry(now = clock::now)
        val first = registry.create("a")
        clock.advance(10L)
        val second = registry.create("b")
        val ids = registry.list().map { it.id }
        assertEquals(listOf(second, first), ids)
    }

    @Test
    fun ttlPrunesFinishedOperations() {
        val clock = FakeClock(10_000L)
        val registry = OperationRegistry(maxEntries = 10, ttlMs = 100L, now = clock::now)
        val finished = registry.create("done")
        registry.succeed(finished, McpToolResult.text("ok"))

        clock.advance(101L)
        registry.create("trigger")

        assertNull(registry.get(finished))
    }

    @Test
    fun ttlKeepsRunningOperations() {
        val clock = FakeClock(10_000L)
        val registry = OperationRegistry(maxEntries = 10, ttlMs = 100L, now = clock::now)
        val running = registry.create("running")

        clock.advance(101L)
        registry.create("trigger")

        assertNotNull(registry.get(running))
    }

    @Test
    fun capacityEvictsOldestFinishedFirst() {
        val clock = FakeClock(0L)
        val registry = OperationRegistry(maxEntries = 3, ttlMs = 10_000L, now = clock::now)
        val running = registry.create("running")

        clock.advance(10L)
        val firstFinished = registry.create("f1")
        registry.succeed(firstFinished, McpToolResult.text("ok"))

        clock.advance(10L)
        val secondFinished = registry.create("f2")
        registry.succeed(secondFinished, McpToolResult.text("ok"))

        clock.advance(10L)
        val newest = registry.create("new")

        assertNotNull(registry.get(running))
        assertNull(registry.get(firstFinished))
        assertNotNull(registry.get(secondFinished))
        assertNotNull(registry.get(newest))
    }

    @Test
    fun capacityFallsBackToOldestRunningWhenNecessary() {
        val clock = FakeClock(0L)
        val registry = OperationRegistry(maxEntries = 1, ttlMs = 10_000L, now = clock::now)
        val first = registry.create("first")

        clock.advance(10L)
        val second = registry.create("second")

        assertNull(registry.get(first))
        assertNotNull(registry.get(second))
    }

    @Test
    fun clearRemovesAll() {
        val registry = OperationRegistry()
        registry.create("a")
        registry.create("b")
        registry.clear()
        assertTrue(registry.list().isEmpty())
    }
}
