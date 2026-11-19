package com.benton.izukijs.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScriptStorageMigrationTest {

    private lateinit var temp: File
    private lateinit var sourceScripts: File
    private lateinit var sourceProjects: File
    private lateinit var targetScripts: File
    private lateinit var targetProjects: File

    @Before
    fun setUp() {
        temp = Files.createTempDirectory("izuki-storage-migration").toFile()
        sourceScripts = File(temp, "source/scripts").apply { mkdirs() }
        sourceProjects = File(temp, "source/projects").apply { mkdirs() }
        targetScripts = File(temp, "target/scripts").apply { mkdirs() }
        targetProjects = File(temp, "target/projects").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        temp.deleteRecursively()
    }

    @Test
    fun copiesScriptsAndProjects() {
        File(sourceScripts, "a.js").writeText("a")
        File(sourceScripts, "b.js").writeText("b")
        File(sourceScripts, "note.txt").writeText("ignored")

        val project = File(sourceProjects, "demo").apply { mkdirs() }
        File(project, "izuki.project.json").writeText("{}")
        File(project, "main.js").writeText("main")
        File(project, "lib").mkdirs()
        File(project, "lib/util.js").writeText("util")
        File(project, "node_modules/pkg").mkdirs()
        File(project, "node_modules/pkg/index.js").writeText("dep")
        File(project, "build").mkdirs()
        File(project, "build/out.js").writeText("out")

        val result = copy()

        assertEquals(5, result.copied)
        assertEquals(0, result.skipped)
        assertEquals(0, result.failed)
        assertTrue(File(targetScripts, "a.js").isFile)
        assertTrue(File(targetScripts, "b.js").isFile)
        assertFalse(File(targetScripts, "note.txt").exists())
        assertTrue(File(targetProjects, "demo/main.js").isFile)
        assertTrue(File(targetProjects, "demo/lib/util.js").isFile)
        assertFalse(File(targetProjects, "demo/node_modules").exists())
        assertFalse(File(targetProjects, "demo/build").exists())
    }

    @Test
    fun skipsExistingFilesWithoutOverwriting() {
        File(sourceScripts, "a.js").writeText("new")
        File(targetScripts, "a.js").writeText("keep")

        val first = copy()

        assertEquals(0, first.copied)
        assertEquals(1, first.skipped)
        assertEquals("keep", File(targetScripts, "a.js").readText())
    }

    @Test
    fun mergesProjectFilesWithoutOverwriting() {
        val project = File(sourceProjects, "demo").apply { mkdirs() }
        File(project, "main.js").writeText("new-main")
        File(project, "extra.js").writeText("extra")
        File(targetProjects, "demo").mkdirs()
        File(targetProjects, "demo/main.js").writeText("keep-main")

        val result = copy()

        assertEquals(1, result.copied)
        assertEquals(1, result.skipped)
        assertEquals("keep-main", File(targetProjects, "demo/main.js").readText())
        assertEquals("extra", File(targetProjects, "demo/extra.js").readText())
    }

    @Test
    fun moveRelocatesProjectDirectory() {
        val source = File(temp, "staging/demo").apply { mkdirs() }
        File(source, "main.js").writeText("main")
        File(source, "assets").mkdirs()
        File(source, "assets/icon.png").writeText("png")
        val target = File(temp, "projects/demo")

        assertTrue(ScriptStorageMigration.move(source, target))

        assertFalse(source.exists())
        assertEquals("main", File(target, "main.js").readText())
        assertEquals("png", File(target, "assets/icon.png").readText())
    }

    @Test
    fun moveRefusesExistingTarget() {
        val source = File(temp, "staging/demo").apply { mkdirs() }
        File(source, "main.js").writeText("main")
        val target = File(temp, "projects/demo").apply { mkdirs() }

        assertFalse(ScriptStorageMigration.move(source, target))
        assertTrue(source.exists())
    }

    private fun copy(): ScriptStorageMigration.Result = ScriptStorageMigration.copy(
        sourceScripts = sourceScripts,
        sourceProjects = sourceProjects,
        targetScripts = targetScripts,
        targetProjects = targetProjects,
    )
}
