package com.benton.izukijs.controller.accessibility

import com.benton.izukijs.controller.NodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorDslTest {

    @Test
    fun parsesExactAndBooleanConditions() {
        val criteria = SelectorDsl.parse("text=登录 clickable=true")
        assertEquals("登录", criteria.text)
        assertEquals(true, criteria.clickable)
    }

    @Test
    fun parsesContainsPrefixAndSuffix() {
        val contains = SelectorDsl.parse("text~=登")
        assertEquals("登", contains.textContains)

        val prefix = SelectorDsl.parse("text^=登")
        assertEquals("^\\Q登\\E", prefix.textRegex)
        assertNull(prefix.text)

        val suffix = SelectorDsl.parse("text$=录")
        assertEquals("\\Q录\\E$", suffix.textRegex)
    }

    @Test
    fun parsesRegexLiteralWithFlags() {
        val criteria = SelectorDsl.parse("text=/^登.*录$/i")
        assertEquals("(?i)^登.*录$", criteria.textRegex)
    }

    @Test
    fun supportsQuotedValuesAndNegatedBooleans() {
        val criteria = SelectorDsl.parse("text=\"立即 登录\" !clickable")
        assertEquals("立即 登录", criteria.text)
        assertEquals(false, criteria.clickable)
    }

    @Test
    fun rejectsUnknownOrInvalidFields() {
        val unknown = runCatching { SelectorDsl.parse("wat=1") }
        assertTrue(unknown.isFailure)
        val invalidBool = runCatching { SelectorDsl.parse("clickable=maybe") }
        assertTrue(invalidBool.isFailure)
        val unclosed = runCatching { SelectorDsl.parse("text=\"abc") }
        assertTrue(unclosed.isFailure)
    }

    @Test
    fun snapshotMatchingUsesAllConditions() {
        val node = NodeSnapshot(
            className = "android.widget.TextView",
            text = "立即登录",
            viewId = "com.example:id/login",
            contentDescription = null,
            left = 0,
            top = 0,
            right = 100,
            bottom = 50,
            clickable = true,
            editable = false,
            children = emptyList(),
            packageName = "com.example",
        )
        assertTrue(SelectorDsl.parse("text~=登录 clickable=true").matchesSnapshot(node))
        assertFalse(SelectorDsl.parse("text~=注册").matchesSnapshot(node))
        assertFalse(SelectorDsl.parse("text~=登录 clickable=false").matchesSnapshot(node))
    }
}
