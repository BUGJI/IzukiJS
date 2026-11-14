package com.benton.izukijs.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptImporterTest {

    @Test
    fun stripsQueryAndExtension() {
        assertEquals(
            "demo",
            ScriptImporter.nameFromUrl("https://example.com/scripts/demo.js?token=abc"),
        )
    }

    @Test
    fun keepsFileNameWithoutExtension() {
        assertEquals("demo", ScriptImporter.nameFromUrl("https://example.com/scripts/demo"))
    }

    @Test
    fun removesOnlyLastExtension() {
        assertEquals("archive.tar", ScriptImporter.nameFromUrl("https://example.com/archive.tar.gz"))
    }

    @Test
    fun trailingSlashFallsBackToImported() {
        assertEquals("imported", ScriptImporter.nameFromUrl("https://example.com/scripts/"))
    }

    @Test
    fun invalidUrlFallsBackToImported() {
        assertEquals("imported", ScriptImporter.nameFromUrl("not a url"))
        assertEquals("imported", ScriptImporter.nameFromUrl(""))
    }

    @Test
    fun dotFileKeepsWholeName() {
        assertEquals(".hidden", ScriptImporter.nameFromUrl("https://example.com/.hidden"))
    }
}
