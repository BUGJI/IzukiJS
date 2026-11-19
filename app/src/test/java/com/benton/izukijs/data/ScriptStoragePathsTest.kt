package com.benton.izukijs.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScriptStoragePathsTest {

    private lateinit var temp: File

    @Before
    fun setUp() {
        temp = Files.createTempDirectory("izuki-storage-paths").toFile()
    }

    @After
    fun tearDown() {
        temp.deleteRecursively()
    }

    @Test
    fun resolvesDefaultAndCustomRoots() {
        val filesDir = File(temp, "files").apply { mkdirs() }
        val external = File(temp, "external").apply { mkdirs() }

        assertEquals(filesDir, ScriptStoragePaths.resolveRoot(filesDir, null))
        assertEquals(filesDir, ScriptStoragePaths.resolveRoot(filesDir, "  "))
        assertEquals(external, ScriptStoragePaths.resolveRoot(filesDir, external.path))

        assertEquals(File(filesDir, "scripts"), ScriptStoragePaths.scriptsDir(filesDir))
        assertEquals(File(filesDir, "projects"), ScriptStoragePaths.projectsDir(filesDir))
    }

    @Test
    fun normalizesAbsoluteDirectory() {
        val target = File(temp, "root").apply { mkdirs() }
        assertEquals(target.canonicalFile, ScriptStoragePaths.normalizeRoot(target.path))
    }

    @Test
    fun rejectsBlankRelativeAndFileSystemRoot() {
        assertNull(ScriptStoragePaths.normalizeRoot(""))
        assertNull(ScriptStoragePaths.normalizeRoot("   "))
        assertNull(ScriptStoragePaths.normalizeRoot("relative/path"))
        assertNull(ScriptStoragePaths.normalizeRoot(File("/").path))
    }

    @Test
    fun detectsSameAndNestedDirectories() {
        val a = File(temp, "a").apply { mkdirs() }
        val nested = File(a, "scripts").apply { mkdirs() }
        val sibling = File(temp, "b").apply { mkdirs() }

        assertTrue(ScriptStoragePaths.isSameOrNested(a, nested))
        assertTrue(ScriptStoragePaths.isSameOrNested(nested, a))
        assertTrue(ScriptStoragePaths.isSameOrNested(a, a))
        assertFalse(ScriptStoragePaths.isSameOrNested(a, sibling))
    }
}
