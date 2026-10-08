package com.benton.izukijs.mcp.tools

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
        val registry = OperationRegistry()
        val first = registry.create("a")
        Thread.sleep(2)
        val second = registry.create("b")
        val ids = registry.list().map { it.id }
        assertEquals(listOf(second, first), ids)
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
