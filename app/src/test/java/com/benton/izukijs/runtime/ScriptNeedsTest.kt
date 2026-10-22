package com.benton.izukijs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScriptNeedsTest {

    @Test
    fun noDeclarationMeansUnrestricted() {
        assertNull(ScriptNeeds.parse("log('hi');"))
    }

    @Test
    fun parsesCommaAndSpaceSeparatedNames() {
        val needs = ScriptNeeds.parse(
            """
            // @need shell, fs
            // @need http
            log('hi');
            """.trimIndent(),
        )
        assertEquals(setOf("shell", "fs", "http"), needs)
    }

    @Test
    fun supportsColonAndDescription() {
        val needs = ScriptNeeds.parse("// @need: shell, fs  需要执行命令并读取文件")
        assertEquals(setOf("shell", "fs"), needs)
    }

    @Test
    fun normalizesCase() {
        assertEquals(setOf("http", "clipboard"), ScriptNeeds.parse("// @need HTTP, Clipboard"))
    }

    @Test
    fun detectsUnknownNamesInManager() {
        val needs = ScriptNeeds.parse("// @need shell, typo")!!
        assertEquals(setOf("typo"), needs - ScriptNeeds.KNOWN)
    }
}
