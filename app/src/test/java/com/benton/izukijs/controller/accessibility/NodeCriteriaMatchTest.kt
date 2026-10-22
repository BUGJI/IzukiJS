package com.benton.izukijs.controller.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeCriteriaMatchTest {

    @Test
    fun emptyConditionMatchesAnything() {
        assertTrue(matchesNodeValue("登录", null, null, null))
        assertTrue(matchesNodeValue(null, null, null, null))
    }

    @Test
    fun exactTextRequiresFullEquality() {
        assertTrue(matchesNodeValue("登录", "登录", null, null))
        assertFalse(matchesNodeValue("立即登录", "登录", null, null))
        assertFalse(matchesNodeValue(null, "登录", null, null))
    }

    @Test
    fun containsMatchesSubstring() {
        assertTrue(matchesNodeValue("立即登录", null, "登录", null))
        assertFalse(matchesNodeValue("注册", null, "登录", null))
        assertFalse(matchesNodeValue(null, null, "登录", null))
    }

    @Test
    fun regexUsesSearchSemantics() {
        val pattern = Regex("登.*录")
        assertTrue(matchesNodeValue("请先登录账号", null, null, pattern))
        assertFalse(matchesNodeValue("注册", null, null, pattern))
        assertFalse(matchesNodeValue(null, null, null, pattern))
    }

    @Test
    fun conditionsAreCombinedWithAnd() {
        assertTrue(matchesNodeValue("立即登录", "立即登录", "登录", Regex("^立即")))
        assertFalse(matchesNodeValue("立即登录", "登录", "登录", null))
        assertFalse(matchesNodeValue("立即登录", "立即登录", "注册", null))
    }
}
