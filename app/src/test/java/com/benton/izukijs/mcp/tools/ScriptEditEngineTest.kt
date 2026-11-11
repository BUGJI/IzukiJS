package com.benton.izukijs.mcp.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ScriptEditEngine] 的 search/replace 语义与失败诊断。 */
class ScriptEditEngineTest {

    private fun edit(old: String, new: String, replaceAll: Boolean = false) =
        ScriptEdit(oldText = old, newText = new, replaceAll = replaceAll)

    @Test
    fun replacesUniqueOccurrence() {
        val source = "fun a() {\n  click(1, 2)\n}\n"
        val result = ScriptEditEngine.apply(source, listOf(edit("click(1, 2)", "click(3, 4)")))
        assertEquals("fun a() {\n  click(3, 4)\n}\n", result.content)
        assertTrue(result.changed)
        val outcome = result.outcomes.single()
        assertEquals(1, outcome.matches)
        assertEquals(2, outcome.line)
    }

    @Test
    fun reportsNotFoundWithNearestLine() {
        val error = assertThrows(ScriptEditException::class.java) {
            ScriptEditEngine.apply("line1\nline2\n", listOf(edit("line2 ", "x")))
        }
        assertEquals("not_found", error.reason)
        assertEquals(0, error.editIndex)
        assertTrue(error.occurrences.isNotEmpty())
        assertEquals(2, error.occurrences.first().line)
    }

    @Test
    fun reportsAmbiguousWithoutReplaceAll() {
        val error = assertThrows(ScriptEditException::class.java) {
            ScriptEditEngine.apply("x\nx\n", listOf(edit("x", "y")))
        }
        assertEquals("ambiguous", error.reason)
        assertEquals(2, error.occurrences.size)
        assertEquals(listOf(1, 2), error.occurrences.map { it.line })
    }

    @Test
    fun replaceAllReplacesEveryOccurrence() {
        val result = ScriptEditEngine.apply("x x x", listOf(edit("x", "y", replaceAll = true)))
        assertEquals("y y y", result.content)
        assertEquals(3, result.outcomes.single().matches)
    }

    @Test
    fun editsApplySequentially() {
        val result = ScriptEditEngine.apply("a", listOf(edit("a", "b"), edit("b", "c")))
        assertEquals("c", result.content)
        assertEquals(2, result.outcomes.size)
    }

    @Test
    fun identicalReplacementIsNoop() {
        val result = ScriptEditEngine.apply("a", listOf(edit("a", "a")))
        assertFalse(result.changed)
        assertEquals(1, result.noopCount)
        assertFalse(result.outcomes.single().changed)
    }

    @Test
    fun emptyOldTextIsRejected() {
        val error = assertThrows(ScriptEditException::class.java) {
            ScriptEditEngine.apply("a", listOf(edit("", "b")))
        }
        assertEquals("invalid", error.reason)
    }

    @Test
    fun emptyEditListIsRejected() {
        val error = assertThrows(ScriptEditException::class.java) {
            ScriptEditEngine.apply("a", emptyList())
        }
        assertEquals("invalid", error.reason)
    }

    @Test
    fun tooManyEditsAreRejected() {
        val edits = List(ScriptEditEngine.MAX_EDITS + 1) { edit("a", "b") }
        val error = assertThrows(ScriptEditException::class.java) {
            ScriptEditEngine.apply("a", edits)
        }
        assertEquals("invalid", error.reason)
    }

    @Test
    fun preservesCrlf() {
        val result = ScriptEditEngine.apply(
            "a\r\nb\r\n",
            listOf(edit("b\r\n", "B\r\n")),
        )
        assertEquals("a\r\nB\r\n", result.content)
    }

    @Test
    fun supportsChineseAndEmoji() {
        val result = ScriptEditEngine.apply(
            "点击(「开始」🙂);",
            listOf(edit("开始", "继续")),
        )
        assertEquals("点击(「继续」🙂);", result.content)
    }

    @Test
    fun multilineOldTextMatchesExactly() {
        val source = "if (a) {\n  click(1, 2)\n}\n"
        val result = ScriptEditEngine.apply(
            source,
            listOf(edit("if (a) {\n  click(1, 2)\n}", "if (b) {\n  click(3, 4)\n}")),
        )
        assertEquals("if (b) {\n  click(3, 4)\n}\n", result.content)
    }
}
