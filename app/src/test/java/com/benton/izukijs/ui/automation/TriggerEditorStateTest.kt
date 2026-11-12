package com.benton.izukijs.ui.automation

import com.benton.izukijs.schedule.Schedule
import com.benton.izukijs.schedule.ScheduleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerEditorStateTest {

    private val now = 1_700_000_000_000L

    @Test
    fun unchangedTimeTriggerKeepsOriginalInstant() {
        val initial = now + 45_000L
        val schedule = Schedule(
            id = "s",
            scriptId = "script",
            scriptName = "script",
            triggerAtMillis = initial,
            intervalMinutes = 0L,
            kind = ScheduleKind.TIME,
        )

        val state = TriggerEditorState.from(schedule)

        assertEquals(initial, state.triggerAtMillis(now))
    }

    @Test
    fun changedTimeTriggerRecomputes() {
        val initial = now + 45_000L
        val schedule = Schedule(
            id = "s",
            scriptId = "script",
            scriptName = "script",
            triggerAtMillis = initial,
            intervalMinutes = 0L,
            kind = ScheduleKind.TIME,
        )

        val state = TriggerEditorState.from(schedule)
        state.minute = ((state.minuteInt() + 1) % 60).toString()

        val next = state.triggerAtMillis(now)
        assertTrue(next == initial + 60_000L || next > now + 60_000L)
    }

    @Test
    fun unchangedIntervalKeepsOriginalInstant() {
        val initial = now + 90_000L
        val schedule = Schedule(
            id = "s",
            scriptId = "script",
            scriptName = "script",
            triggerAtMillis = initial,
            intervalMinutes = 5L,
            kind = ScheduleKind.INTERVAL,
        )

        val state = TriggerEditorState.from(schedule)

        assertEquals(initial, state.triggerAtMillis(now))
    }

    @Test
    fun changedIntervalUsesNowPlusInterval() {
        val schedule = Schedule(
            id = "s",
            scriptId = "script",
            scriptName = "script",
            triggerAtMillis = now + 90_000L,
            intervalMinutes = 5L,
            kind = ScheduleKind.INTERVAL,
        )

        val state = TriggerEditorState.from(schedule)
        state.interval = "10"

        assertEquals(now + 10 * 60_000L, state.triggerAtMillis(now))
    }
}
