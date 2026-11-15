package com.benton.izukijs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RunQueueTest {

    @Test
    fun offerOverwritesAndReturnsPrevious() {
        val queue = RunQueue<String>()

        assertNull(queue.offer("first"))
        assertEquals("first", queue.offer("second"))
        assertEquals("second", queue.peek())
    }

    @Test
    fun takeClearsSlot() {
        val queue = RunQueue<String>()
        queue.offer("only")

        assertEquals("only", queue.take())
        assertNull(queue.take())
        assertNull(queue.peek())
    }

    @Test
    fun clearReturnsPendingItem() {
        val queue = RunQueue<Any>()
        val item = Any()
        queue.offer(item)

        assertSame(item, queue.clear())
        assertNull(queue.peek())
    }

    @Test
    fun emptyQueueReturnsNull() {
        val queue = RunQueue<String>()

        assertNull(queue.take())
        assertNull(queue.clear())
    }
}
