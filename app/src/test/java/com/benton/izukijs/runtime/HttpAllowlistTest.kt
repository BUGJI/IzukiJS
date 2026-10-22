package com.benton.izukijs.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpAllowlistTest {

    @Test
    fun emptyAllowlistAllowsAnyHost() {
        assertTrue(isHttpHostAllowed("example.com", ""))
        assertTrue(isHttpHostAllowed("api.example.com", "   "))
    }

    @Test
    fun exactHostMatches() {
        assertTrue(isHttpHostAllowed("example.com", "example.com"))
        assertTrue(isHttpHostAllowed("EXAMPLE.COM", "example.com"))
    }

    @Test
    fun subdomainsMatchButLookalikesDoNot() {
        assertTrue(isHttpHostAllowed("api.example.com", "example.com"))
        assertFalse(isHttpHostAllowed("notexample.com", "example.com"))
        assertFalse(isHttpHostAllowed("example.com.evil.test", "example.com"))
    }

    @Test
    fun wildcardAndUrlEntriesAreNormalized() {
        assertTrue(isHttpHostAllowed("a.example.com", "*.example.com"))
        assertTrue(isHttpHostAllowed("api.example.com", "https://example.com/path"))
        assertTrue(isHttpHostAllowed("api.example.com", "example.com:8443"))
    }

    @Test
    fun multipleEntriesAndSeparatorsWork() {
        val list = "example.com, api.test;foo.org\nbar.net"
        assertTrue(isHttpHostAllowed("api.example.com", list))
        assertTrue(isHttpHostAllowed("api.test", list))
        assertTrue(isHttpHostAllowed("foo.org", list))
        assertTrue(isHttpHostAllowed("bar.net", list))
        assertFalse(isHttpHostAllowed("other.dev", list))
    }

    @Test
    fun blankHostNeverMatches() {
        assertFalse(isHttpHostAllowed("", ""))
        assertFalse(isHttpHostAllowed("  ", "example.com"))
    }
}
