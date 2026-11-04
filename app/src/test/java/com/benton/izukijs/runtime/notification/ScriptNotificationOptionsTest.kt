package com.benton.izukijs.runtime.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptNotificationOptionsTest {

    @Test
    fun parsesColorFormats() {
        assertEquals(0xFFFF0000.toInt(), ScriptNotificationOptions.parseColor("#F00"))
        assertEquals(0x88FF0000.toInt(), ScriptNotificationOptions.parseColor("#8F00"))
        assertEquals(0xFF2196F3.toInt(), ScriptNotificationOptions.parseColor("#2196F3"))
        assertEquals(0x802196F3.toInt(), ScriptNotificationOptions.parseColor("#802196F3"))
        assertNull(ScriptNotificationOptions.parseColor("red"))
        assertNull(ScriptNotificationOptions.parseColor("#12345"))
    }

    @Test
    fun segmentsDefineMaxAndClampValue() {
        val options = options(
            present = setOf("segments", "value"),
            value = 150,
            segments = listOf(
                ProgressSegment(30),
                ProgressSegment(70),
            ),
        )

        val state = ScriptNotificationState(active = true).applyOptions(
            options,
            allowProgress = true,
        )

        assertEquals(100, state.max)
        assertEquals(100, state.value)
        assertFalse(state.indeterminate)
    }

    @Test
    fun partialUpdateKeepsExistingFields() {
        val initial = ScriptNotificationState(
            active = true,
            title = "old",
            text = "old text",
            stage = "old stage",
            value = 20,
            max = 100,
            indeterminate = false,
        )
        val updated = initial.applyOptions(
            options(present = setOf("text"), text = "new text"),
            allowProgress = true,
        )

        assertEquals("old", updated.title)
        assertEquals("new text", updated.text)
        assertEquals("old stage", updated.stage)
        assertEquals(20, updated.value)
    }

    @Test
    fun progressFieldsIgnoredWhenDisabled() {
        val initial = ScriptNotificationState(active = true, text = "old")
        val updated = initial.applyOptions(
            options(
                present = setOf("text", "value", "max", "segments"),
                text = "new",
                value = 50,
                max = 200,
                segments = listOf(ProgressSegment(10)),
            ),
            allowProgress = false,
        )

        assertEquals("new", updated.text)
        assertEquals(initial.value, updated.value)
        assertEquals(initial.max, updated.max)
        assertTrue(updated.segments.isEmpty())
    }

    @Test
    fun invalidMaxFallsBackToIndeterminate() {
        val state = ScriptNotificationState(active = true).applyOptions(
            options(present = setOf("max"), max = 0),
            allowProgress = true,
        )

        assertEquals(ScriptNotificationState.DEFAULT_PROGRESS_MAX, state.max)
        assertTrue(state.indeterminate)
    }

    @Test
    fun pointsAreClampedToMax() {
        val state = ScriptNotificationState(active = true).applyOptions(
            options(
                present = setOf("max", "points"),
                max = 100,
                points = listOf(ProgressPoint(150), ProgressPoint(40)),
            ),
            allowProgress = true,
        )

        assertEquals(listOf(100, 40), state.points.map { it.position })
    }

    private fun options(
        present: Set<String>,
        title: String? = null,
        text: String? = null,
        value: Int? = null,
        max: Int? = null,
        segments: List<ProgressSegment>? = null,
        points: List<ProgressPoint>? = null,
    ) = ScriptNotificationOptions(
        present = present,
        title = title,
        text = text,
        value = value,
        max = max,
        segments = segments,
        points = points,
    )
}
