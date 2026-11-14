package com.benton.izukijs.schedule

import com.benton.izukijs.model.UnlockResult
import com.benton.izukijs.testing.FakeClock
import com.benton.izukijs.testing.FakeRunConditions
import com.benton.izukijs.testing.FakeRunLogSink
import com.benton.izukijs.testing.FakeRunUnlocker
import com.benton.izukijs.testing.FakeScheduleLifecycle
import com.benton.izukijs.testing.FakeScheduleStore
import com.benton.izukijs.testing.FakeScriptRunner
import com.benton.izukijs.testing.FakeScriptSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleRunnerTest {

    private val clock = FakeClock(200_000L)
    private val scripts = FakeScriptSource().apply {
        add("s1", name = "Script 1")
        add("s2", name = "Script 2")
    }
    private val store = FakeScheduleStore()
    private val lifecycle = FakeScheduleLifecycle()
    private val execution = FakeScriptRunner()
    private val unlocker = FakeRunUnlocker()
    private val conditions = FakeRunConditions()
    private val log = FakeRunLogSink()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun runner() = ScheduleRunner(
        repository = store,
        scriptRepository = scripts,
        scheduleManager = lifecycle,
        executionManager = execution,
        screenUnlock = unlocker,
        triggerConditions = conditions,
        logBus = log,
        now = clock::now,
        scope = scope,
    )

    private fun schedule(
        id: String = "sched",
        scriptId: String = "s1",
        scriptName: String = "Script 1",
        kind: ScheduleKind = ScheduleKind.TIME,
        event: ScheduleEvent? = null,
        lastTriggeredAtMillis: Long = 0L,
    ) = Schedule(
        id = id,
        scriptId = scriptId,
        scriptName = scriptName,
        triggerAtMillis = 0L,
        intervalMinutes = 0L,
        kind = kind,
        event = event,
        lastTriggeredAtMillis = lastTriggeredAtMillis,
    )

    @Test
    fun skipsWhenScriptAlreadyRunning() {
        execution.running.value = true
        val schedule = schedule()

        runner().run(schedule)

        assertEquals(listOf(schedule), lifecycle.skipped.toList())
        assertTrue(execution.scheduledRuns.isEmpty())
    }

    @Test
    fun skipsWhenConditionsAreNotMet() {
        conditions.met = false
        val schedule = schedule()

        runner().run(schedule)

        assertEquals(listOf(schedule), lifecycle.skipped.toList())
        assertTrue(execution.scheduledRuns.isEmpty())
    }

    @Test
    fun cancelsWhenScriptIsMissing() {
        val schedule = schedule(scriptId = "missing", scriptName = "Missing")

        runner().run(schedule)

        assertEquals(listOf("sched"), lifecycle.cancelled.toList())
        assertTrue(execution.scheduledRuns.isEmpty())
    }

    @Test
    fun startsScriptAndWiresCompletion() {
        val schedule = schedule()

        runner().run(schedule)

        val run = execution.scheduledRuns.single()
        assertEquals("s1", run.scriptId)
        assertEquals(com.benton.izukijs.runtime.RunTrigger.SCHEDULE, run.trigger)
        assertEquals(clock.now(), store.upserts.single().lastTriggeredAtMillis)
        assertEquals(1, lifecycle.finished.size)
        assertEquals(true, lifecycle.finished.single().second)
        assertEquals(clock.now(), lifecycle.finished.single().first.lastTriggeredAtMillis)
    }

    @Test
    fun reportsSkippedWhenStartIsRejected() {
        execution.scheduledResult = false
        val schedule = schedule()

        runner().run(schedule)

        assertEquals(listOf(schedule), lifecycle.skipped.toList())
        assertTrue(lifecycle.finished.isEmpty())
    }

    @Test
    fun unlockFailureIsLoggedButDoesNotBlockRun() {
        unlocker.enabled = true
        unlocker.result = UnlockResult.NO_BACKEND
        val schedule = schedule()

        runner().run(schedule)

        assertEquals(1, unlocker.ensureCalls)
        assertEquals(1, execution.scheduledRuns.size)
        assertTrue(log.warnLines.any { it.contains("自动解锁未完成") })
    }

    @Test
    fun unlockIsSkippedWhenDisabled() {
        unlocker.enabled = false

        runner().run(schedule())

        assertEquals(0, unlocker.ensureCalls)
    }

    @Test
    fun eventRunsOnlyMatchingAndNotDebouncedSchedule() {
        val debounced = schedule(
            id = "debounced",
            kind = ScheduleKind.EVENT,
            event = ScheduleEvent.WIFI,
            lastTriggeredAtMillis = clock.now() - 10_000L,
        )
        val eligible = schedule(
            id = "eligible",
            scriptId = "s2",
            scriptName = "Script 2",
            kind = ScheduleKind.EVENT,
            event = ScheduleEvent.WIFI,
            lastTriggeredAtMillis = clock.now() - 70_000L,
        )
        val otherEvent = schedule(
            id = "other",
            kind = ScheduleKind.EVENT,
            event = ScheduleEvent.CHARGING,
        )
        val store = FakeScheduleStore(listOf(debounced, eligible, otherEvent))
        val runner = ScheduleRunner(
            repository = store,
            scriptRepository = scripts,
            scheduleManager = lifecycle,
            executionManager = execution,
            screenUnlock = unlocker,
            triggerConditions = conditions,
            logBus = log,
            now = clock::now,
            scope = scope,
        )

        runner.onEvent(ScheduleEvent.WIFI)

        assertEquals(listOf("s2"), execution.scheduledRuns.map { it.scriptId })
        assertTrue(store.upserts.any { it.id == "eligible" })
        assertTrue(store.upserts.none { it.id == "debounced" })
    }
}
