package com.benton.izukijs.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualScreenTextInputTest {

    @Test
    fun plainAsciiUsesInputText() {
        assertFalse(VirtualScreenTextInput.needsClipboard("hello"))
        assertFalse(VirtualScreenTextInput.needsClipboard("hello world 123"))
        assertFalse(VirtualScreenTextInput.needsClipboard(""))
    }

    @Test
    fun nonAsciiAndNewlinesUseClipboard() {
        assertTrue(VirtualScreenTextInput.needsClipboard("你好"))
        assertTrue(VirtualScreenTextInput.needsClipboard("emoji 😀"))
        assertTrue(VirtualScreenTextInput.needsClipboard("line\nbreak"))
        assertTrue(VirtualScreenTextInput.needsClipboard("tab\t"))
    }

    @Test
    fun spacesAreEncoded() {
        assertEquals("'hello%sworld'", VirtualScreenTextInput.escapeForInput("hello world"))
    }

    @Test
    fun singleQuotesAreClosedAndReopened() {
        assertEquals("'it'\\''s'", VirtualScreenTextInput.escapeForInput("it's"))
    }
}
