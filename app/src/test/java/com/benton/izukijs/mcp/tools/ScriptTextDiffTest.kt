package com.benton.izukijs.mcp.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ScriptTextDiff] 的 unified diff 输出与超限降级。 */
class ScriptTextDiffTest {

    @Test
    fun unchangedTextHasEmptyDiff() {
        assertEquals("", ScriptTextDiff.unified("a\nb", "a\nb"))
    }

    @Test
    fun reportsReplacedLine() {
        val diff = ScriptTextDiff.unified("a\nb\nc", "a\nB\nc")
        assertTrue(diff.startsWith("@@ -1,3 +1,3 @@"))
        assertTrue(diff.contains("-b"))
        assertTrue(diff.contains("+B"))
        assertTrue(diff.contains(" a"))
        assertTrue(diff.contains(" c"))
    }

    @Test
    fun reportsAppendedLine() {
        val diff = ScriptTextDiff.unified("a", "a\nb")
        assertTrue(diff.startsWith("@@ -1,1 +1,2 @@"))
        assertTrue(diff.contains("+b"))
    }

    @Test
    fun reportsInsertionIntoEmptyFile() {
        val diff = ScriptTextDiff.unified("", "a\n")
        assertTrue(diff.startsWith("@@ -1,0 +1,2 @@"))
        assertTrue(diff.contains("+a"))
    }

    @Test
    fun fallsBackToSummaryForLargeChanges() {
        val before = (1..1100).joinToString("\n") { "old-$it" }
        val after = (1..1100).joinToString("\n") { "new-$it" }
        val diff = ScriptTextDiff.unified(before, after)
        assertTrue(diff.startsWith("（变更过大，已省略逐行 diff）"))
        assertTrue(diff.contains("1100"))
    }
}
