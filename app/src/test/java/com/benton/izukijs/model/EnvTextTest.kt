package com.benton.izukijs.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvTextTest {

    @Test
    fun parseReadsKeyValuePairsAndIgnoresComments() {
        val result = EnvText.parse(
            """
            A=1
            # comment

            B=
            C=x=y
            """.trimIndent(),
        )

        assertTrue(result is EnvParseResult.Success)
        assertEquals(
            mapOf("A" to "1", "B" to "", "C" to "x=y"),
            (result as EnvParseResult.Success).values,
        )
    }

    @Test
    fun parseRejectsInvalidKeyWithLineNumber() {
        val result = EnvText.parse("A=1\n1BAD=2")

        assertTrue(result is EnvParseResult.Failure)
        assertEquals(2, (result as EnvParseResult.Failure).lineNumber)
    }

    @Test
    fun parseRejectsLineWithoutEquals() {
        val result = EnvText.parse("A=1\nBROKEN")

        assertTrue(result is EnvParseResult.Failure)
        assertEquals(2, (result as EnvParseResult.Failure).lineNumber)
    }

    @Test
    fun parseLastDuplicateWins() {
        val result = EnvText.parse("A=1\nA=2")

        assertTrue(result is EnvParseResult.Success)
        assertEquals(mapOf("A" to "2"), (result as EnvParseResult.Success).values)
    }

    @Test
    fun resolveAppliesGlobalAndScriptOverrides() {
        val resolved = EnvText.resolve(
            defaults = mapOf("A" to "default", "D" to "keep"),
            global = mapOf("A" to "global", "G" to "global-only"),
            script = mapOf("A" to "script", "S" to "script-only"),
            runtime = mapOf("A" to "runtime"),
            useGlobal = true,
        )

        assertEquals(
            mapOf(
                "A" to "runtime",
                "D" to "keep",
                "G" to "global-only",
                "S" to "script-only",
            ),
            resolved,
        )
    }

    @Test
    fun resolveSkipsGlobalWhenDisabled() {
        val resolved = EnvText.resolve(
            defaults = mapOf("A" to "default"),
            global = mapOf("A" to "global", "G" to "global-only"),
            script = mapOf("A" to "script"),
            runtime = emptyMap(),
            useGlobal = false,
        )

        assertEquals(mapOf("A" to "script"), resolved)
    }

    @Test
    fun formatSortsByKey() {
        assertEquals("A=1\nB=2", EnvText.format(mapOf("B" to "2", "A" to "1")))
    }
}
