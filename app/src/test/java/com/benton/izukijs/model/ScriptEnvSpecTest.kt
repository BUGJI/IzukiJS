package com.benton.izukijs.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptEnvSpecTest {

    @Test
    fun parsesFieldsInDeclarationOrder() {
        val spec = ScriptEnvSpec.parse(
            """
            // @env MODE=auto   运行模式
            // @env TARGET=com.example
            """.trimIndent(),
        )

        assertEquals(listOf("MODE", "TARGET"), spec.fields.map { it.key })
        assertEquals(mapOf("MODE" to "auto", "TARGET" to "com.example"), spec.defaults())
        assertEquals("运行模式", spec.fields.first().label)
        assertEquals("", spec.fields.last().label)
    }

    @Test
    fun duplicateKeyLastWins() {
        val spec = ScriptEnvSpec.parse(
            """
            // @env A=1
            // @env A=2
            """.trimIndent(),
        )

        assertEquals(1, spec.fields.size)
        assertEquals("2", spec.fields.single().default)
    }

    @Test
    fun labelCanBeSeparatedByHash() {
        val spec = ScriptEnvSpec.parse("// @env KEY=value # 说明")

        assertEquals("value", spec.fields.single().default)
        assertEquals("说明", spec.fields.single().label)
    }

    @Test
    fun valueMayContainEqualsAndStayEmpty() {
        val spec = ScriptEnvSpec.parse(
            """
            // @env EMPTY=
            // @env FORMULA=a=b  说明
            """.trimIndent(),
        )

        assertEquals("", spec.fields[0].default)
        assertEquals("a=b", spec.fields[1].default)
    }

    @Test
    fun ignoresMalformedLines() {
        val spec = ScriptEnvSpec.parse(
            """
            // @env =missing
            // @env 1BAD=x
            // @env OK=1
            @env NOT_A_COMMENT=1
            """.trimIndent(),
        )

        assertEquals(listOf("OK"), spec.fields.map { it.key })
    }

    @Test
    fun emptySourceHasNoFields() {
        val spec = ScriptEnvSpec.parse("console.log('hi')")

        assertTrue(spec.isEmpty)
        assertTrue(spec.defaults().isEmpty())
    }
}
