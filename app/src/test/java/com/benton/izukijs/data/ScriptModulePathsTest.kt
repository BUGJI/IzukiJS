package com.benton.izukijs.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ScriptModulePathsTest {

    private lateinit var root: File
    private lateinit var scripts: File
    private lateinit var from: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("izuki-project").toFile()
        scripts = Files.createTempDirectory("izuki-scripts").toFile()
        from = File(root, "main.js").apply { writeText("") }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        scripts.deleteRecursively()
    }

    @Test
    fun resolvesRelativeFilesAndIndex() {
        write("lib/utils.js")
        write("lib/index.js")

        assertEquals(File(root, "lib/utils.js").canonicalFile, resolve("./lib/utils"))
        assertEquals(File(root, "lib/index.js").canonicalFile, resolve("./lib"))
        assertEquals(File(root, "lib/utils.js").canonicalFile, resolve("lib/utils"))
    }

    @Test
    fun resolvesJsonAndPackageMain() {
        write("data.json", "{}")
        write("pkg/package.json", """{"main":"entry"}""")
        write("pkg/entry.js")

        assertEquals(File(root, "data.json").canonicalFile, resolve("./data"))
        assertEquals(File(root, "pkg/entry.js").canonicalFile, resolve("./pkg"))
    }

    @Test
    fun rejectsPathEscape() {
        val outside = Files.createTempFile("izuki-outside", ".js").toFile()
        try {
            outside.writeText("")
            assertNull(resolve("../" + outside.name))
            assertNull(resolve("./../../" + outside.name))
        } finally {
            outside.delete()
        }
    }

    @Test
    fun bareNameFallsBackToFlatScripts() {
        File(scripts, "common.js").writeText("")
        write("lib/util.js")
        assertEquals(File(scripts, "common.js").canonicalFile, resolve("common"))
        assertEquals(File(root, "lib/util.js").canonicalFile, resolve("./lib/util.js"))
    }

    private fun resolve(name: String): File? =
        ScriptModulePaths.resolve(name, from, root, scripts)

    private fun write(relative: String, content: String = "") {
        val file = File(root, relative)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}
