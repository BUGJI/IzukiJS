package com.benton.izukijs.ui.common

import com.benton.izukijs.model.EnvField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptLaunchDecisionTest {

    @Test
    fun noEnvDeclarationsRunDirectly() {
        val decision = decideScriptLaunch(
            source = "console.log('hi')",
            stored = emptyMap(),
            global = emptyMap(),
            script = emptyMap(),
            useGlobal = true,
        )

        assertTrue(decision is ScriptLaunchDecision.Direct)
        assertEquals("console.log('hi')", (decision as ScriptLaunchDecision.Direct).source)
    }

    @Test
    fun envDeclarationsNeedDialog() {
        val source = """
            // @env A=default-a   说明
            // @env B=
        """.trimIndent()

        val decision = decideScriptLaunch(
            source = source,
            stored = emptyMap(),
            global = emptyMap(),
            script = emptyMap(),
            useGlobal = true,
        ) as ScriptLaunchDecision.NeedsEnv

        assertEquals(
            listOf(
                EnvField(key = "A", default = "default-a", label = "说明"),
                EnvField(key = "B", default = "", label = ""),
            ),
            decision.fields,
        )
        assertEquals(mapOf("A" to "default-a", "B" to ""), decision.initial)
    }

    @Test
    fun initialRespectsPrecedence() {
        val source = """
            // @env A=default-a
            // @env B=default-b
            // @env C=default-c
        """.trimIndent()

        val decision = decideScriptLaunch(
            source = source,
            stored = mapOf("A" to "stored-a"),
            global = mapOf("A" to "global-a", "B" to "global-b"),
            script = mapOf("A" to "script-a", "C" to "script-c"),
            useGlobal = true,
        ) as ScriptLaunchDecision.NeedsEnv

        assertEquals("stored-a", decision.initial["A"])
        assertEquals("global-b", decision.initial["B"])
        assertEquals("script-c", decision.initial["C"])
    }

    @Test
    fun globalIsIgnoredWhenDisabled() {
        val decision = decideScriptLaunch(
            source = "// @env A=default-a",
            stored = emptyMap(),
            global = mapOf("A" to "global-a"),
            script = emptyMap(),
            useGlobal = false,
        ) as ScriptLaunchDecision.NeedsEnv

        assertEquals("default-a", decision.initial["A"])
    }
}
