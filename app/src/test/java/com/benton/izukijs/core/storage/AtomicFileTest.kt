package com.benton.izukijs.core.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun createsFileAndParentDirectory() {
        val target = File(tempFolder.root, "nested/config.json")

        AtomicFile.writeText(target, "{\"a\":1}")

        assertTrue(target.exists())
        assertEquals("{\"a\":1}", target.readText())
    }

    @Test
    fun supportsShortFileNames() {
        val target = File(tempFolder.root, "a")

        AtomicFile.writeText(target, "short")

        assertEquals("short", target.readText())
    }

    @Test
    fun overwritesExistingContent() {
        val target = tempFolder.newFile("config.json").apply { writeText("old") }

        AtomicFile.writeText(target, "new")

        assertEquals("new", target.readText())
    }

    @Test
    fun leavesNoTemporaryFilesBehind() {
        val target = File(tempFolder.root, "config.json")

        AtomicFile.writeText(target, "content")

        val leftovers = tempFolder.root.listFiles()
            .orEmpty()
            .filter { it.name.endsWith(".tmp") }
        assertTrue(leftovers.isEmpty())
    }

    @Test
    fun keepsOneCompleteContentUnderConcurrentWrites() {
        val target = File(tempFolder.root, "config.json")
        val contents = (0 until 8).map { index -> "value-$index" }

        contents.map { content ->
            Thread { AtomicFile.writeText(target, content) }.apply { start() }
        }.forEach { it.join() }

        val finalContent = target.readText()
        assertTrue(finalContent in contents)
        assertFalse(finalContent.contains("\u0000"))
    }
}
