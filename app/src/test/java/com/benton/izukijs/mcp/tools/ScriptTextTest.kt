package com.benton.izukijs.mcp.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ScriptText] 的行号、分页与原始换行保留。 */
class ScriptTextTest {

    @Test
    fun lineCountMatchesKotlinLinesSemantics() {
        assertEquals(0, ScriptText.lineCount(""))
        assertEquals(1, ScriptText.lineCount("a"))
        assertEquals(2, ScriptText.lineCount("a\n"))
        assertEquals(3, ScriptText.lineCount("a\nb\nc"))
    }

    @Test
    fun slicePreservesCrlfAndTrailingNewline() {
        val text = "a\r\nb\r\nc"
        val slice = ScriptText.slice(text, offset = 1, limit = 1)
        assertEquals("b\r\n", slice.content)
        assertEquals(1, slice.offset)
        assertEquals(1, slice.count)
        assertEquals(3, slice.total)
        assertTrue(slice.truncated)
        assertEquals(2, slice.nextOffset)
    }

    @Test
    fun sliceWithZeroLimitReturnsRemainder() {
        val slice = ScriptText.slice("a\nb\nc", offset = 1, limit = 0)
        assertEquals("b\nc", slice.content)
        assertFalse(slice.truncated)
        assertEquals(3, slice.nextOffset)
    }

    @Test
    fun slicePastEndIsEmpty() {
        val slice = ScriptText.slice("a\nb", offset = 10, limit = 5)
        assertEquals("", slice.content)
        assertEquals(2, slice.offset)
        assertEquals(0, slice.count)
        assertFalse(slice.truncated)
    }

    @Test
    fun numberLinesOmitsTrailingBlankLine() {
        val numbered = ScriptText.numberLines("a\nb\n", startLine = 5)
        assertEquals("5 | a\n6 | b", numbered)
    }

    @Test
    fun lineAtStripsCarriageReturn() {
        assertEquals("b", ScriptText.lineAt("a\r\nb\r\n", 2))
        assertEquals("", ScriptText.lineAt("a\n", 2))
        assertEquals("", ScriptText.lineAt("a\n", 99))
    }

    @Test
    fun previewAtTruncatesLongLine() {
        val long = "x".repeat(200)
        val preview = ScriptText.previewAt(long, 10, maxChars = 20)
        assertEquals("x".repeat(20) + "…", preview)
    }

    @Test
    fun lineOfCountsNewlines() {
        assertEquals(1, ScriptText.lineOf("abc", 0))
        assertEquals(2, ScriptText.lineOf("a\nb", 2))
        assertEquals(3, ScriptText.lineOf("a\nb\nc", 4))
    }
}
