package com.benton.izukijs.flow

import com.benton.izukijs.runtime.ScriptRunOutcome
import com.benton.izukijs.schedule.ScheduleConditions
import com.benton.izukijs.schedule.ScheduleEvent
import com.benton.izukijs.schedule.ScheduleKind
import com.benton.izukijs.testing.FakeClock
import com.benton.izukijs.testing.FakeFlowNotifier
import com.benton.izukijs.testing.FakeFlowScheduler
import com.benton.izukijs.testing.FakeFlowStore
import com.benton.izukijs.testing.FakeRunConditions
import com.benton.izukijs.testing.FakeRunLogSink
import com.benton.izukijs.testing.FakeRunUnlocker
import com.benton.izukijs.testing.FakeScriptRunner
import com.benton.izukijs.testing.FakeScriptSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowRunnerTest {

    private val clock = FakeClock(100_000L)
    private val scripts = FakeScriptSource().apply {
        add("a")
        add("b")
        add("c")
        add("evt-a")
        add("evt-b")
    }
    private val store = FakeFlowStore()
    private val scheduler = FakeFlowScheduler()
    private val execution = FakeScriptRunner()
    private val unlocker = FakeRunUnlocker()
    private val conditions = FakeRunConditions()
    private val log = FakeRunLogSink()
    private val notifier = FakeFlowNotifier()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun runner() = FlowRunner(
        repository = store,
        scriptRepository = scripts,
        flowManager = scheduler,
        executionManager = execution,
        screenUnlock = unlocker,
        triggerConditions = conditions,
        logBus = log,
        notificationDispatcher = notifier,
        now = clock::now,
        scope = scope,
    )

    private fun step(id: String) = FlowStep(id = id, scriptId = id, scriptName = id)

    private fun flow(
        steps: List<FlowStep>,
        id: String = "flow",
        name: String = "Flow",
        continueOnError: Boolean = true,
        kind: ScheduleKind = ScheduleKind.TIME,
        event: ScheduleEvent? = null,
        enabled: Boolean = true,
        lastTriggeredAtMillis: Long = 0L,
        conditions: ScheduleConditions = ScheduleConditions(),
    ) = ScriptFlow(
        id = id,
        name = name,
        kind = kind,
        event = event,
        steps = steps,
        continueOnError = continueOnError,
        enabled = enabled,
        lastTriggeredAtMillis = lastTriggeredAtMillis,
        conditions = conditions,
    )

    private fun awaitStarted() {
        runBlocking { withTimeout(2_000L) { execution.started.await() } }
    }

    @Test
    fun emptyFlowReturnsEmptyAndFinishesOnce() {
        val flow = flow(emptyList())
        val result = runner().run(flow)

        assertEquals(FlowRunStart.EMPTY, result)
        assertEquals(listOf(flow to false), scheduler.finished.toList())
        assertTrue(execution.completionRuns.isEmpty())
        assertTrue(notifier.summaries.isEmpty())
    }

    @Test
    fun emptyFlowDoesNotTouchScheduleWhenManual() {
        val result = runner().run(flow(emptyList()), manual = true)

        assertEquals(FlowRunStart.EMPTY, result)
        assertTrue(scheduler.finished.isEmpty())
    }

    @Test
    fun runsStepsInConfiguredOrder() {
        val flow = flow(listOf(step("a"), step("b"), step("c")))
        val result = runner().run(flow)

        assertEquals(FlowRunStart.STARTED, result)
        assertEquals(listOf("a", "b", "c"), execution.completionRuns.map { it.scriptId })
        assertEquals(listOf(true), scheduler.finished.map { it.second })
        assertEquals(3, notifier.summaries.single().succeeded)
        assertEquals(3, notifier.summaries.single().total)
        assertEquals(true, notifier.summaries.single().allSucceeded)
        assertEquals(clock.now(), store.upserts.single().lastTriggeredAtMillis)
    }

    @Test
    fun rejectsWhenScriptAlreadyRunning() {
        execution.running.value = true
        val flow = flow(listOf(step("a")))

        assertEquals(FlowRunStart.BUSY, runner().run(flow))
        assertEquals(listOf(flow), scheduler.skipped.toList())
        assertTrue(execution.completionRuns.isEmpty())
    }

    @Test
    fun rejectsSecondFlowWhileFirstIsRunning() {
        execution.prepareGate()
        val runner = runner()
        val first = flow(listOf(step("a")), id = "first")
        val second = flow(listOf(step("b")), id = "second")

        assertEquals(FlowRunStart.STARTED, runner.run(first))
        awaitStarted()
        assertEquals(FlowRunStart.BUSY, runner.run(second))
        assertEquals(listOf(second), scheduler.skipped.toList())

        execution.gate!!.complete(Unit)
        assertTrue(scheduler.finished.any { it.first.id == "first" })
    }

    @Test
    fun stopsAfterFailureWhenContinueOnErrorDisabled() {
        execution.outcomes["a"] = ScriptRunOutcome.Finished(false)
        val flow = flow(listOf(step("a"), step("b")), continueOnError = false)

        runner().run(flow)

        assertEquals(listOf("a"), execution.completionRuns.map { it.scriptId })
        assertEquals(listOf(false), scheduler.finished.map { it.second })
    }

    @Test
    fun continuesAfterFailureWhenEnabled() {
        execution.outcomes["a"] = ScriptRunOutcome.Finished(false)
        val flow = flow(listOf(step("a"), step("b")), continueOnError = true)

        runner().run(flow)

        assertEquals(listOf("a", "b"), execution.completionRuns.map { it.scriptId })
        assertEquals(listOf(false), scheduler.finished.map { it.second })
        assertEquals(1, notifier.summaries.single().succeeded)
        assertEquals(2, notifier.summaries.single().executed)
    }

    @Test
    fun missingScriptFailsAndStopsWhenConfigured() {
        val flow = flow(
            listOf(step("a"), FlowStep("missing", "missing", "missing"), step("b")),
            continueOnError = false,
        )

        runner().run(flow)

        assertEquals(listOf("a"), execution.completionRuns.map { it.scriptId })
        assertEquals(listOf(false), scheduler.finished.map { it.second })
    }

    @Test
    fun invalidOutcomeStopsWhenConfigured() {
        execution.outcomes["a"] = ScriptRunOutcome.Invalid("bad")
        val flow = flow(listOf(step("a"), step("b")), continueOnError = false)

        runner().run(flow)

        assertEquals(listOf("a"), execution.completionRuns.map { it.scriptId })
    }

    @Test
    fun stoppedOutcomeStopsEvenWhenErrorsAreIgnored() {
        execution.outcomes["a"] = ScriptRunOutcome.Stopped
        val flow = flow(listOf(step("a"), step("b")), continueOnError = true)

        runner().run(flow)

        assertEquals(listOf("a"), execution.completionRuns.map { it.scriptId })
    }

    @Test
    fun busyOutcomeAbortsFlow() {
        execution.outcomes["a"] = ScriptRunOutcome.Busy
        val flow = flow(listOf(step("a"), step("b")), continueOnError = true)

        runner().run(flow)

        assertEquals(listOf("a"), execution.completionRuns.map { it.scriptId })
        assertEquals(listOf(false), scheduler.finished.map { it.second })
    }

    @Test
    fun manualRunDoesNotUpdateScheduleOrLifecycle() {
        val flow = flow(listOf(step("a")))
        runner().run(flow, manual = true)

        assertTrue(store.upserts.isEmpty())
        assertTrue(scheduler.finished.isEmpty())
        assertTrue(scheduler.skipped.isEmpty())
        assertEquals(1, notifier.summaries.size)
    }

    @Test
    fun conditionFailureSkipsWithoutRunning() {
        conditions.met = false
        val flow = flow(listOf(step("a")))

        runner().run(flow)

        assertTrue(execution.completionRuns.isEmpty())
        assertEquals(listOf(flow), scheduler.skipped.toList())
    }

    @Test
    fun manualRunIgnoresConditions() {
        conditions.met = false
        val flow = flow(listOf(step("a")))

        runner().run(flow, manual = true)

        assertEquals(listOf("a"), execution.completionRuns.map { it.scriptId })
        assertTrue(scheduler.skipped.isEmpty())
    }

    @Test
    fun stopStopsActiveFlow() {
        execution.prepareGate()
        val runner = runner()
        val flow = flow(listOf(step("a"), step("b")))

        assertEquals(FlowRunStart.STARTED, runner.run(flow))
        awaitStarted()
        assertTrue(runner.stop(flow.id))
        assertEquals(1, execution.stopCount)

        execution.gate!!.complete(Unit)

        assertEquals(listOf("a"), execution.completionRuns.map { it.scriptId })
        assertEquals(listOf(false), scheduler.finished.map { it.second })
    }

    @Test
    fun stopUnknownFlowReturnsFalse() {
        assertFalse(runner().stop("missing"))
    }

    @Test
    fun notifierFailureDoesNotBreakCompletion() {
        notifier.failure = RuntimeException("boom")
        val flow = flow(listOf(step("a")))

        runner().run(flow)

        assertEquals(listOf(true), scheduler.finished.map { it.second })
        assertTrue(log.warnLines.any { it.contains("通知派发失败") })
    }

    @Test
    fun eventRunsOnlyMatchingEnabledAndNotDebouncedFlow() {
        val debounced = flow(
            listOf(step("evt-a")),
            id = "debounced",
            kind = ScheduleKind.EVENT,
            event = ScheduleEvent.WIFI,
            lastTriggeredAtMillis = clock.now() - 10_000L,
        )
        val eligible = flow(
            listOf(step("evt-b")),
            id = "eligible",
            kind = ScheduleKind.EVENT,
            event = ScheduleEvent.WIFI,
            lastTriggeredAtMillis = clock.now() - 70_000L,
        )
        val otherEvent = flow(
            listOf(step("evt-a")),
            id = "other",
            kind = ScheduleKind.EVENT,
            event = ScheduleEvent.CHARGING,
        )
        val disabled = flow(
            listOf(step("evt-a")),
            id = "disabled",
            kind = ScheduleKind.EVENT,
            event = ScheduleEvent.WIFI,
            enabled = false,
        )
        val store = FakeFlowStore(listOf(debounced, eligible, otherEvent, disabled))
        val runner = FlowRunner(
            repository = store,
            scriptRepository = scripts,
            flowManager = scheduler,
            executionManager = execution,
            screenUnlock = unlocker,
            triggerConditions = conditions,
            logBus = log,
            notificationDispatcher = notifier,
            now = clock::now,
            scope = scope,
        )

        runner.onEvent(ScheduleEvent.WIFI)

        assertEquals(listOf("evt-b"), execution.completionRuns.map { it.scriptId })
        assertTrue(store.upserts.any { it.id == "eligible" })
        assertFalse(store.upserts.any { it.id == "debounced" })
    }
}
