package com.benton.izukijs.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密码框脱敏回归：只对密码框的 text_changed 清空内容，其他事件保持原样。
 */
class A11yEventSanitizerTest {

    @Test
    fun passwordTextChangeIsRedacted() {
        assertTrue(shouldRedactA11yText(eventType = "text_changed", isPassword = true))
    }

    @Test
    fun passwordOtherEventsKeepContent() {
        assertFalse(shouldRedactA11yText(eventType = "view_clicked", isPassword = true))
        assertFalse(shouldRedactA11yText(eventType = "view_scrolled", isPassword = true))
        assertFalse(shouldRedactA11yText(eventType = null, isPassword = true))
    }

    @Test
    fun nonPasswordTextChangeIsKept() {
        assertFalse(shouldRedactA11yText(eventType = "text_changed", isPassword = false))
    }
}
