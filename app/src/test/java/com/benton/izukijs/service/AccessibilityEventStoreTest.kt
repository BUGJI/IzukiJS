package com.benton.izukijs.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccessibilityEventStoreTest {

    @Before
    fun setUp() {
        AccessibilityEventStore.clear()
    }

    @After
    fun tearDown() {
        AccessibilityEventStore.clear()
    }

    @Test
    fun recordsSequenceAndFiltersByType() {
        // 序号跨 clear 单调递增，测试只断言相对增量，避免依赖进程内的绝对起点。
        val start = AccessibilityEventStore.sequence()
        val first = AccessibilityEventStore.record(
            type = "window_state",
            packageName = "com.example",
            className = "MainActivity",
            time = 1_000L,
        )
        AccessibilityEventStore.record(type = "key_down", keyCode = 24, time = 2_000L)

        assertEquals(start + 1, first.seq)
        assertEquals(start + 2, AccessibilityEventStore.sequence())
        val window = AccessibilityEventStore.snapshot(types = setOf("window_state"))
        assertEquals(1, window.size)
        assertEquals("com.example", window.first().packageName)
        assertEquals(0, AccessibilityEventStore.snapshot(sinceSeq = start + 2).size)
    }

    @Test
    fun clearKeepsSequenceMonotonicAndWaitersStillWake() {
        AccessibilityEventStore.record(type = "window_state", time = 1_000L)
        val before = AccessibilityEventStore.sequence()

        AccessibilityEventStore.clear()

        // 序号不能回退：已记下 afterSeq 的等待方否则会一直等到超时。
        assertEquals(before, AccessibilityEventStore.sequence())
        assertEquals(0, AccessibilityEventStore.snapshot().size)

        val worker = Thread {
            Thread.sleep(60)
            AccessibilityEventStore.record(type = "view_clicked")
        }
        worker.start()
        val changed = AccessibilityEventStore.awaitChange(before, 1_000L)
        worker.join()
        assertTrue(changed)
        assertEquals(before + 1, AccessibilityEventStore.sequence())
    }

    @Test
    fun newestEventIsFirstAndBufferIsBounded() {
        repeat(205) { index ->
            AccessibilityEventStore.record(type = "window_content", text = "event-$index", time = index.toLong())
        }
        val snapshot = AccessibilityEventStore.snapshot(limit = 500)
        assertEquals(200, snapshot.size)
        assertEquals("event-204", snapshot.first().text)
        assertEquals("event-5", snapshot.last().text)
    }

    @Test
    fun awaitChangeWakesWhenEventArrives() {
        val start = AccessibilityEventStore.sequence()
        val worker = Thread {
            Thread.sleep(60)
            AccessibilityEventStore.record(type = "view_clicked")
        }
        worker.start()
        val changed = AccessibilityEventStore.awaitChange(start, 1_000L)
        worker.join()
        assertTrue(changed)
        assertFalse(AccessibilityEventStore.awaitChange(AccessibilityEventStore.sequence(), 80L))
    }

    @Test
    fun lastEventAtRespectsTypeFilter() {
        AccessibilityEventStore.record(type = "window_state", time = 1_000L)
        AccessibilityEventStore.record(type = "key_down", time = 5_000L)
        assertEquals(1_000L, AccessibilityEventStore.lastEventAt(setOf("window_state")))
        assertEquals(5_000L, AccessibilityEventStore.lastEventAt(null))
    }
}
