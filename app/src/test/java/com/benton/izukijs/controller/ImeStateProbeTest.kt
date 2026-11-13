package com.benton.izukijs.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImeStateProbeTest {

    @Test
    fun detectsShownIme() {
        val dump = """
            InputMethodService:
              mInputShown=true
        """.trimIndent()
        assertEquals(true, ImeStateProbe.isImeShown(dump))
    }

    @Test
    fun detectsHiddenIme() {
        val dump = """
            InputMethodService:
              mInputShown=false
              mShowRequested=false
        """.trimIndent()
        assertEquals(false, ImeStateProbe.isImeShown(dump))
    }

    @Test
    fun prefersAnyShownDisplay() {
        val dump = "mInputShown=false\nmInputShown = true\n"
        assertEquals(true, ImeStateProbe.isImeShown(dump))
    }

    @Test
    fun returnsNullWhenFieldMissing() {
        assertNull(ImeStateProbe.isImeShown("no relevant output"))
        assertNull(ImeStateProbe.isImeShown(null))
        assertNull(ImeStateProbe.isImeShown(""))
    }
}
