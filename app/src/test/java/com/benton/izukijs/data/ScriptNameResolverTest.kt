package com.benton.izukijs.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptNameResolverTest {

    @Test
    fun displayNameTakesPriorityOverDocumentId() {
        assertEquals(
            "my_script",
            ScriptNameResolver.fromDocument("my_script.js", "msf:1000000123"),
        )
    }

    @Test
    fun mediaStoreDocumentIdDoesNotBecomeName() {
        assertEquals("imported", ScriptNameResolver.fromDocument(null, "msf:1000000123"))
        assertEquals("imported", ScriptNameResolver.fromDocument(null, "image:42"))
        assertEquals("imported", ScriptNameResolver.fromDocument(null, "123456"))
    }

    @Test
    fun externalStorageDocumentIdYieldsFileName() {
        assertEquals(
            "foo",
            ScriptNameResolver.fromDocument(null, "primary:Download/foo.js"),
        )
    }

    @Test
    fun documentIdWithRealNameAfterColonIsKept() {
        assertEquals("backup", ScriptNameResolver.fromDocument(null, "msf:backup.js"))
    }

    @Test
    fun onlyKnownExtensionsAreStripped() {
        assertEquals("v1.2", ScriptNameResolver.fromDocument("v1.2", null))
        assertEquals("a.b", ScriptNameResolver.fromDocument("a.b.js", null))
        assertEquals("plain", ScriptNameResolver.fromDocument("plain.TXT", null))
    }

    @Test
    fun blankInputsFallBackToDefault() {
        assertEquals("imported", ScriptNameResolver.fromDocument(null, null))
        assertEquals("imported", ScriptNameResolver.fromDocument("  ", "  "))
    }

    @Test
    fun blankDisplayNameFallsBackToSegment() {
        assertEquals("real", ScriptNameResolver.fromDocument("  ", "real.js"))
    }
}
