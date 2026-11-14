package com.benton.izukijs.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RunPreparationTest {

    private val source = """
        // @env A=default-a   默认值
        // @env B=default-b
        // @env D=default-d
    """.trimIndent()

    @Test
    fun appliesPrecedenceDefaultsGlobalScriptSavedAndRuntime() {
        val resolved = RunPreparation.resolveEnv(
            source = source,
            useGlobal = true,
            global = mapOf("A" to "global-a", "G" to "global-only"),
            script = mapOf("A" to "script-a", "S" to "script-only"),
            savedValues = mapOf("A" to "saved-a", "R" to "saved-only"),
            values = mapOf("A" to "runtime-a"),
        )

        assertEquals(
            mapOf(
                "A" to "runtime-a",
                "B" to "default-b",
                "D" to "default-d",
                "G" to "global-only",
                "S" to "script-only",
                "R" to "saved-only",
            ),
            resolved,
        )
    }

    @Test
    fun savedValueOverridesScriptValue() {
        val resolved = RunPreparation.resolveEnv(
            source = source,
            useGlobal = false,
            global = emptyMap(),
            script = mapOf("A" to "script-a"),
            savedValues = mapOf("A" to "saved-a"),
            values = emptyMap(),
        )

        assertEquals("saved-a", resolved["A"])
    }

    @Test
    fun globalIsIgnoredWhenDisabled() {
        val resolved = RunPreparation.resolveEnv(
            source = source,
            useGlobal = false,
            global = mapOf("A" to "global-a", "G" to "global-only"),
            script = mapOf("A" to "script-a"),
            savedValues = emptyMap(),
            values = emptyMap(),
        )

        assertEquals("script-a", resolved["A"])
        assertEquals(false, resolved.containsKey("G"))
    }

    @Test
    fun emptySourceStillMergesRuntimeValues() {
        val resolved = RunPreparation.resolveEnv(
            source = "console.log('hi')",
            useGlobal = true,
            global = mapOf("G" to "1"),
            script = emptyMap(),
            savedValues = emptyMap(),
            values = mapOf("V" to "2"),
        )

        assertEquals(mapOf("G" to "1", "V" to "2"), resolved)
    }
}
