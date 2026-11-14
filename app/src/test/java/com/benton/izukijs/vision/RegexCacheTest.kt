package com.benton.izukijs.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RegexCacheTest {

    @Test
    fun compilesAndReusesPattern() {
        val warnings = mutableListOf<String>()
        val cache = RegexCache({ warnings += it }, "test")

        val first = cache.get("a+")
        val second = cache.get("a+")

        assertSame(first, second)
        assertTrue(first!!.matches("aaa"))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun invalidPatternIsCachedAndWarnedOnce() {
        val warnings = mutableListOf<String>()
        val cache = RegexCache({ warnings += it }, "OCR")

        assertNull(cache.get("("))
        assertNull(cache.get("("))

        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("OCR"))
        assertTrue(warnings.single().contains("("))
    }

    @Test
    fun evictsLeastRecentlyUsedEntry() {
        val warnings = mutableListOf<String>()
        val cache = RegexCache({ warnings += it }, "test", maxEntries = 2)

        cache.get("(")
        assertEquals(1, warnings.size)

        cache.get("a")
        cache.get("b")
        cache.get("a")
        cache.get("c")
        cache.get("d")

        assertNull(cache.get("("))
        assertEquals(2, warnings.size)
    }
}
