package com.benton.izukijs.testing

import com.benton.izukijs.core.log.RunLogSink
import com.benton.izukijs.data.ScriptSource
import com.benton.izukijs.flow.FlowNotifier
import com.benton.izukijs.flow.FlowScheduler
import com.benton.izukijs.flow.FlowStore
import com.benton.izukijs.flow.ScriptFlow
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.model.UnlockResult
import com.benton.izukijs.runtime.RunTrigger
import com.benton.izukijs.runtime.RunUnlocker
import com.benton.izukijs.runtime.ScriptRunOutcome
import com.benton.izukijs.runtime.ScriptRunner
import com.benton.izukijs.schedule.RunConditions
import com.benton.izukijs.schedule.Schedule
import com.benton.izukijs.schedule.ScheduleConditions
import com.benton.izukijs.schedule.ScheduleLifecycle
import com.benton.izukijs.schedule.ScheduleStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

data class RecordedRun(
    val scriptId: String,
    val scriptName: String,
    val source: String,
    val trigger: RunTrigger,
)

class FakeScriptRunner : ScriptRunner {

    override val running = MutableStateFlow(false)

    val scheduledRuns = CopyOnWriteArrayList<RecordedRun>()
    val completionRuns = CopyOnWriteArrayList<RecordedRun>()

    var scheduledResult: Boolean = true
    val outcomes = mutableMapOf<String, ScriptRunOutcome>()
    var stopCount: Int = 0
        private set

    var started: CompletableDeferred<Unit> = CompletableDeferred()
    var gate: CompletableDeferred<Unit>? = null

    fun prepareGate() {
        started = CompletableDeferred()
        gate = CompletableDeferred()
    }

    override fun requestStop() {
        stopCount++
    }

    override fun runScheduled(
        scriptId: String,
        scriptName: String,
        source: String,
        trigger: RunTrigger,
        onFinished: (Boolean) -> Unit,
    ): Boolean {
        scheduledRuns += RecordedRun(scriptId, scriptName, source, trigger)
        if (scheduledResult) onFinished(true)
        return scheduledResult
    }

    override suspend fun runToCompletion(
        scriptId: String,
        scriptName: String,
        source: String,
        trigger: RunTrigger,
    ): ScriptRunOutcome {
        completionRuns += RecordedRun(scriptId, scriptName, source, trigger)
        started.complete(Unit)
        gate?.await()
        return outcomes[scriptId] ?: ScriptRunOutcome.Finished(true)
    }
}

class FakeScriptSource : ScriptSource {

    private val scripts = LinkedHashMap<String, ScriptInfo>()
    private val sources = HashMap<String, String>()

    fun add(id: String, name: String = id, source: String = "// $id"): ScriptInfo {
        val info = ScriptInfo(id = id, name = name, file = File("$id.js"), updatedAt = 0L)
        scripts[id] = info
        sources[id] = source
        return info
    }

    override fun find(id: String): ScriptInfo? = scripts[id]

    override fun read(script: ScriptInfo): String = sources[script.id].orEmpty()
}

class FakeFlowStore(initial: List<ScriptFlow> = emptyList()) : FlowStore {

    var flows: List<ScriptFlow> = initial
        private set

    val upserts = CopyOnWriteArrayList<ScriptFlow>()

    override fun list(): List<ScriptFlow> = flows

    override fun upsert(flow: ScriptFlow) {
        upserts += flow
        flows = flows.filterNot { it.id == flow.id } + flow
    }
}

class FakeFlowScheduler : FlowScheduler {

    val finished = CopyOnWriteArrayList<Pair<ScriptFlow, Boolean>>()
    val skipped = CopyOnWriteArrayList<ScriptFlow>()

    override fun onFinished(flow: ScriptFlow, success: Boolean) {
        finished += flow to success
    }

    override fun onSkipped(flow: ScriptFlow) {
        skipped += flow
    }
}

class FakeFlowNotifier : FlowNotifier {

    data class Summary(
        val flow: ScriptFlow,
        val executed: Int,
        val succeeded: Int,
        val total: Int,
        val allSucceeded: Boolean,
        val durationMs: Long,
    )

    val summaries = CopyOnWriteArrayList<Summary>()
    var failure: Throwable? = null

    override fun summary(
        flow: ScriptFlow,
        executed: Int,
        succeeded: Int,
        total: Int,
        allSucceeded: Boolean,
        durationMs: Long,
    ) {
        failure?.let { throw it }
        summaries += Summary(flow, executed, succeeded, total, allSucceeded, durationMs)
    }
}

class FakeRunUnlocker : RunUnlocker {

    var enabled: Boolean = false
    var result: UnlockResult = UnlockResult.OK
    var ensureCalls: Int = 0
        private set

    override fun isEnabled(): Boolean = enabled

    override suspend fun ensureUnlocked(): UnlockResult {
        ensureCalls++
        return result
    }
}

class FakeRunConditions : RunConditions {

    var met: Boolean = true
    val checked = CopyOnWriteArrayList<ScheduleConditions>()

    override fun met(conditions: ScheduleConditions): Boolean {
        checked += conditions
        return met
    }
}

class FakeScheduleStore(initial: List<Schedule> = emptyList()) : ScheduleStore {

    var schedules: List<Schedule> = initial
        private set

    val upserts = CopyOnWriteArrayList<Schedule>()

    override fun list(): List<Schedule> = schedules

    override fun upsert(schedule: Schedule) {
        upserts += schedule
        schedules = schedules.filterNot { it.id == schedule.id } + schedule
    }
}

class FakeScheduleLifecycle : ScheduleLifecycle {

    val finished = CopyOnWriteArrayList<Pair<Schedule, Boolean>>()
    val skipped = CopyOnWriteArrayList<Schedule>()
    val cancelled = CopyOnWriteArrayList<String>()

    override fun onFinished(schedule: Schedule, success: Boolean) {
        finished += schedule to success
    }

    override fun onSkipped(schedule: Schedule) {
        skipped += schedule
    }

    override fun cancel(id: String) {
        cancelled += id
    }
}

class FakeRunLogSink : RunLogSink {

    val debugLines = CopyOnWriteArrayList<String>()
    val infoLines = CopyOnWriteArrayList<String>()
    val warnLines = CopyOnWriteArrayList<String>()
    val errorLines = CopyOnWriteArrayList<String>()
    val successLines = CopyOnWriteArrayList<String>()

    override fun debug(message: String) {
        debugLines += message
    }

    override fun info(message: String) {
        infoLines += message
    }

    override fun warn(message: String) {
        warnLines += message
    }

    override fun error(message: String) {
        errorLines += message
    }

    override fun success(message: String) {
        successLines += message
    }
}

/** 条件求值替身，避免依赖电池 / Wi-Fi / 锁屏系统服务。 */
class FakeConditionProbe(
    var charging: Boolean = false,
    var onWifi: Boolean = false,
    var locked: Boolean = false,
    var foreground: String? = null,
) : com.benton.izukijs.schedule.ConditionProbe {

    var chargingCalls = 0
    var wifiCalls = 0
    var lockedCalls = 0
    var foregroundCalls = 0

    override fun isCharging(): Boolean {
        chargingCalls++
        return charging
    }

    override fun isOnWifi(): Boolean {
        wifiCalls++
        return onWifi
    }

    override fun isLocked(): Boolean {
        lockedCalls++
        return locked
    }

    override fun foregroundPackage(): String? {
        foregroundCalls++
        return foreground
    }
}
