package com.benton.izukijs.runtime.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptApiCatalogTest {

    @Test
    fun namespaceNamesAreUniqueAndNonBlank() {
        val names = ScriptApiCatalog.namespaces.map { it.name }
        assertEquals(names.size, names.distinct().size)
        assertTrue(names.all { it.isNotBlank() })
    }

    @Test
    fun needKeysAreUniqueLowercaseAndKnown() {
        val needs = ScriptApiCatalog.namespaces.mapNotNull { it.need }
        assertEquals(needs.size, needs.distinct().size)
        assertTrue(needs.all { it == it.lowercase() && it.isNotBlank() })
        assertEquals(needs.toSet(), ScriptApiCatalog.needKeys)
    }

    @Test
    fun everyEntryHasSuggestions() {
        for (entry in ScriptApiCatalog.all) {
            assertFalse("${entry.name} 缺少补全", entry.suggestions.isEmpty())
            assertEquals(
                "${entry.name} 补全重复",
                entry.suggestions.size,
                entry.suggestions.distinct().size,
            )
        }
    }

    @Test
    fun needKeysMatchScriptNeedsKnown() {
        assertEquals(ScriptApiCatalog.needKeys, com.benton.izukijs.runtime.ScriptNeeds.KNOWN)
    }
}
