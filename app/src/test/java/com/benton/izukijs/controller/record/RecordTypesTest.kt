package com.benton.izukijs.controller.record

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordTypesTest {

    @Test
    fun roundTripsEvents() {
        val events = listOf(
            ScreenshotEvent(1, "frames/1.jpg", 1080, 2400),
            ScreenshotEvent(1, "frames/0.jpg", 1080, 2400, stale = true),
            ClickEvent(2, 10, 20, true),
            LongClickEvent(3, 11, 21, 600, false),
            SwipeEvent(4, 1, 2, 3, 4, 200, true),
            GestureEvent(5, JSONArray().put(JSONArray().put(1).put(2)), true),
            PressKeyEvent(6, 4, true),
            InputTextEvent(7, "hello", "AUTO", true),
            GlobalActionEvent(8, 1, true),
            ShellEvent(9, "echo hi", "hi", "", 0, true),
            AppEvent(10, "start", "com.example", true),
        )

        for (event in events) {
            val decoded = RecordEvent.fromJson(event.toJson())
            assertEquals(event.toJson().toString(), decoded?.toJson()?.toString())
        }
    }

    @Test
    fun exposesActionKinds() {
        assertEquals(RecordActionKind.CLICK, RecordEvent.kindOf(ClickEvent(0, 0, 0, true)))
        assertEquals(RecordActionKind.SHELL, RecordEvent.kindOf(ShellEvent(0, "x", "", "", 0, true)))
        assertEquals(null, RecordEvent.kindOf(ScreenshotEvent(0, "f", 1, 1)))
    }

    @Test
    fun keepsFailureState() {
        assertTrue(!RecordEvent.successOf(ClickEvent(0, 0, 0, false)))
        assertTrue(RecordEvent.successOf(ShellEvent(0, "x", "", "", 0, true)))
    }
}
