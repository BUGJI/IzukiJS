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
    fun parsesExtendedFilterFields() {
        val criteria = SelectorDsl.parse(
            "id^=login class~=Button package=com.example " +
                "checked=true longClickable visible",
        )
        assertEquals("login", criteria.viewIdStartsWith)
        assertEquals("Button", criteria.classNameContains)
        assertEquals("com.example", criteria.packageName)
        assertEquals(true, criteria.checked)
        assertEquals(true, criteria.longClickable)
        assertEquals(true, criteria.visible)
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

    @Test
    fun snapshotMatchingSupportsExtendedFields() {
        val node = NodeSnapshot(
            className = "android.widget.CheckBox",
            text = "记住我",
            viewId = "com.example:id/remember",
            contentDescription = null,
            left = 10,
            top = 20,
            right = 110,
            bottom = 70,
            clickable = true,
            editable = false,
            children = emptyList(),
            packageName = "com.example",
            checkable = true,
            checked = true,
            focusable = true,
            focused = false,
            selected = false,
            longClickable = true,
            password = false,
        )
        assertTrue(
            SelectorDsl.parse(
                "id~=remember class~=CheckBox package=com.example " +
                    "checkable=true checked=true boundsInside=0,0,200,100",
            ).matchesSnapshot(node),
        )
        assertFalse(SelectorDsl.parse("checked=false").matchesSnapshot(node))
        assertFalse(SelectorDsl.parse("bounds=0,0,100,50").matchesSnapshot(node))
    }
}
