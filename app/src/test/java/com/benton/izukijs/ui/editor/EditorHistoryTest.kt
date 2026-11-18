package com.benton.izukijs.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorHistoryTest {

    private var now = 1_000L
    private val history = EditorHistory(coalesceMs = 500L, clock = { now })

    private fun state(text: String, start: Int = text.length) =
        EditorTextState(text, start, start)

    @Test
    fun undoAndRedoRestoreSnapshots() {
        val first = state("a")
        val second = state("ab")

        history.record(first, force = true)
        assertEquals(first, history.undo(second))
        assertTrue(history.canRedo)

        assertEquals(second, history.redo(first))
        assertFalse(history.canRedo)
        assertTrue(history.canUndo)
    }

    @Test
    fun rapidTypingIsCoalescedIntoOneStep() {
        val first = state("a")
        val second = state("ab")

        history.record(first, force = false)
        now += 100L
        history.record(second, force = false)

        assertEquals(first, history.undo(second))
        assertNull(history.undo(first))
    }

    @Test
    fun typingAfterCoalesceWindowCreatesNewStep() {
        val first = state("a")
        val second = state("ab")

        history.record(first, force = false)
        now += 1_000L
        history.record(second, force = false)

        assertEquals(second, history.undo(state("abc")))
        assertEquals(first, history.undo(second))
    }

    @Test
    fun forceRecordsEvenInsideCoalesceWindow() {
        val first = state("a")
        val second = state("b")

        history.record(first, force = true)
        now += 10L
        history.record(second, force = true)

        assertEquals(second, history.undo(state("c")))
        assertEquals(first, history.undo(second))
    }

    @Test
    fun newEditClearsRedoStack() {
        val first = state("a")
        history.record(first, force = true)
        history.undo(state("b"))
        assertTrue(history.canRedo)

        history.record(state("c"), force = true)
        assertFalse(history.canRedo)
    }

    @Test
    fun stackIsBoundedByMaxEntries() {
        val history = EditorHistory(maxEntries = 2, clock = { now })
        history.record(state("0"), force = true)
        history.record(state("1"), force = true)
        history.record(state("2"), force = true)

        assertEquals(state("2"), history.undo(state("3")))
        assertEquals(state("1"), history.undo(state("2")))
        assertNull(history.undo(state("1")))
    }

    @Test
    fun resetCoalescingStartsNewStep() {
        val first = state("a")
        val second = state("ab")
        history.record(first, force = false)
        now += 10L
        history.resetCoalescing()
        history.record(second, force = false)

        assertEquals(second, history.undo(state("abc")))
        assertEquals(first, history.undo(second))
    }
}
