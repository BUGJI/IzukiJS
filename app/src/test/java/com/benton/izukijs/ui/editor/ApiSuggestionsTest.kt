package com.benton.izukijs.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiSuggestionsTest {

    @Test
    fun extractsTokenAtCursor() {
        assertEquals("sel", ApiSuggestions.tokenAt("log('x'); sel", 13))
        assertEquals("selector.fi", ApiSuggestions.tokenAt("selector.fi", 11))
        assertEquals("app.", ApiSuggestions.tokenAt("app.", 4))
        assertNull(ApiSuggestions.tokenAt("log('x'); ", 11))
    }

    @Test
    fun suggestsTopLevelNamespacesAndFunctions() {
        val namespaces = ApiSuggestions.suggestions("htt")
        assertEquals("http.", namespaces.first().text)
        assertTrue(ApiSuggestions.suggestions("wai").any { it.text == "waitUntil(" })
    }

    @Test
    fun suggestsMembersAfterDot() {
        val results = ApiSuggestions.suggestions("app.la").map { it.text }
        assertTrue(results.contains("app.launch("))
        assertTrue(results.contains("app.lastUsed("))
        assertTrue(results.all { it.startsWith("app.la") })
    }

    @Test
    fun emptyMemberPrefixShowsMembers() {
        val results = ApiSuggestions.suggestions("http.", limit = 3)
        assertEquals(3, results.size)
        assertTrue(results.all { it.text.startsWith("http.") })
    }

    @Test
    fun tokenStartMatchesReplacementRange() {
        val text = "log('x'); selector.fi"
        assertEquals(10, ApiSuggestions.tokenStart(text, text.length))
    }
}
