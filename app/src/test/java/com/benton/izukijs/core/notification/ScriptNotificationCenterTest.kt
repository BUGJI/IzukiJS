package com.benton.izukijs.core.notification

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptNotificationCenterTest {

    @After
    fun tearDown() {
        ScriptNotificationCenter.clear()
    }

    @Test
    fun staleRunCannotClearNewNotification() {
        ScriptNotificationCenter.begin("run-a", "Script A")
        ScriptNotificationCenter.begin("run-b", "Script B")

        ScriptNotificationCenter.clear("run-a")

        assertTrue(ScriptNotificationCenter.isActive)
        assertEquals("Script B", ScriptNotificationCenter.state.value.scriptName)
    }

    @Test
    fun staleRunCannotUpdateNewNotification() {
        ScriptNotificationCenter.begin("run-a", "Script A")
        ScriptNotificationCenter.begin("run-b", "Script B")

        val updated = ScriptNotificationCenter.update(
            ScriptNotificationOptions(present = setOf("text"), text = "hijack"),
            allowProgress = false,
            runId = "run-a",
        )

        assertFalse(updated)
        assertEquals("Script B", ScriptNotificationCenter.state.value.text)
    }

    @Test
    fun staleRunCannotResetNewNotification() {
        ScriptNotificationCenter.begin("run-a", "Script A")
        ScriptNotificationCenter.begin("run-b", "Script B")
        ScriptNotificationCenter.update(
            ScriptNotificationOptions(
                present = setOf("value", "max", "indeterminate"),
                value = 42,
                max = 100,
                indeterminate = false,
            ),
            allowProgress = true,
            runId = "run-b",
        )

        assertFalse(ScriptNotificationCenter.reset("run-a"))
        assertEquals(42, ScriptNotificationCenter.state.value.value)
    }

    @Test
    fun ownerCanClearNotification() {
        ScriptNotificationCenter.begin("run-a", "Script A")

        ScriptNotificationCenter.clear("run-a")

        assertFalse(ScriptNotificationCenter.isActive)
    }

    @Test
    fun unscopedClearStillWorksForUserStop() {
        ScriptNotificationCenter.begin("run-a", "Script A")

        ScriptNotificationCenter.clear()

        assertFalse(ScriptNotificationCenter.isActive)
    }
}
