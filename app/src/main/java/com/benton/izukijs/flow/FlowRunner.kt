package com.benton.izukijs.flow

import com.benton.izukijs.core.log.RunLogSink
import com.benton.izukijs.data.ScriptSource
import com.benton.izukijs.model.UnlockResult
import com.benton.izukijs.runtime.RunTrigger
import com.benton.izukijs.runtime.RunUnlocker
import com.benton.izukijs.runtime.ScriptRunOutcome
import com.benton.izukijs.runtime.ScriptRunner
import com.benton.izukijs.schedule.RunConditions
import com.benton.izukijs.schedule.ScheduleEvent
import com.benton.izukijs.schedule.ScheduleKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** 立即执行编排的同步受理结果，用于给用户真实反馈。 */
enum class FlowRunStart { STARTED, BUSY, EMPTY }

/**
 * 脚本编排执行器：将步骤串行交给 [ScriptRunner]，一步结束后再启动下一步。
 *
 * 执行期间不依赖 UI 生命周期。手动「立即执行」不会改动编排的触发时间或状态。
 */
class FlowRunner(
    private val repository: FlowStore,
    private val scriptRepository: ScriptSource,
    private val flowManager: FlowScheduler,
    private val executionManager: ScriptRunner,
    private val screenUnlock: RunUnlocker,
    private val triggerConditions: RunConditions,
    private val logBus: RunLogSink,
    private val notificationDispatcher: FlowNotifier,
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val eventMutex = Mutex()
    private val running = AtomicBoolean(false)

    @Volatile
    private var activeFlowId: String? = null

    @Volatile
    private var stopRequested = false

    /** 立即执行一条编排；[manual] 为 true 时不修改触发调度。 */
    fun run(flow: ScriptFlow, manual: Boolean = false): FlowRunStart {
        if (flow.steps.isEmpty()) {
            logBus.error("编排没有可执行步骤: ${flow.name}")
            if (!manual) flowManager.onFinished(flow, false)
            return FlowRunStart.EMPTY
        }
        if (!running.compareAndSet(false, true)) {
            logBus.warn("编排跳过：已有编排正在运行（${flow.name}）")
            if (!manual) flowManager.onSkipped(flow)
            return FlowRunStart.BUSY
        }
        if (executionManager.running.value) {
            running.set(false)
            logBus.warn("编排跳过：已有脚本正在运行（${flow.name}）")
            if (!manual) flowManager.onSkipped(flow)
            return FlowRunStart.BUSY
        }
        scope.launch {
            try {
                runInternal(flow, manual)
            } finally {
                running.set(false)
            }
        }
        return FlowRunStart.STARTED
    }

    /** 请求停止当前编排；返回 false 表示该编排当前未运行。 */
    fun stop(flowId: String): Boolean {
        if (activeFlowId != flowId) return false
        stopRequested = true
        executionManager.requestStop()
        return true
    }

    fun onEvent(event: ScheduleEvent) {
        scope.launch {
            eventMutex.withLock {
                val current = now()
                repository.list()
                    .filter { it.enabled && it.kind == ScheduleKind.EVENT && it.event == event }
                    .filter { current - it.lastTriggeredAtMillis >= EVENT_DEBOUNCE_MS }
                    .forEach { flow ->
                        val updated = flow.copy(lastTriggeredAtMillis = current)
                        repository.upsert(updated)
                        logBus.info("⚡ 编排事件触发: ${flow.name} (${event.name.lowercase()})")
                        run(updated)
                    }
            }
        }
    }

    private suspend fun runInternal(flow: ScriptFlow, manual: Boolean) {
        if (executionManager.running.value) {
            logBus.warn("编排跳过：已有脚本正在运行（${flow.name}）")
            if (!manual) flowManager.onSkipped(flow)
            return
        }
        if (screenUnlock.isEnabled()) {
            val result = screenUnlock.ensureUnlocked()
            if (result != UnlockResult.OK &&
                result != UnlockResult.ALREADY_UNLOCKED &&
                result != UnlockResult.DISABLED
            ) {
                logBus.warn("编排自动解锁未完成: $result")
            }
        }
        if (!manual && !triggerConditions.met(flow.conditions)) {
            logBus.info("编排条件未满足，跳过本次: ${flow.name}")
            flowManager.onSkipped(flow)
            return
        }
        if (flow.steps.isEmpty()) {
            logBus.error("编排没有可执行步骤: ${flow.name}")
            if (!manual) flowManager.onFinished(flow, false)
            return
        }

        val steps = FlowPlanner.orderedSteps(flow.steps, flow.order)
        val runFlow = if (manual) {
            flow
        } else {
            flow.copy(lastTriggeredAtMillis = now()).also(repository::upsert)
        }
        activeFlowId = runFlow.id
        stopRequested = false
        val startedAt = now()
        logBus.info(
            "▶ 编排开始: ${runFlow.name}（${steps.size} 步，${orderLabel(runFlow.order)}）",
        )

        val results = mutableListOf<StepResult>()
        try {
            for ((index, step) in steps.withIndex()) {
                if (stopRequested) {
                    logBus.warn("⏹ 编排已停止: ${runFlow.name}")
                    break
                }
                val result = runStep(step, index + 1, steps.size)
                results += result
                if (stopRequested) {
                    logBus.warn("⏹ 编排已停止: ${runFlow.name}")
                    break
                }
                if (result.stopped) {
                    logBus.warn("编排已停止：当前步骤被用户终止")
                    break
                }
                if (result.busy) {
                    logBus.warn("编排中止：已有其他脚本在运行")
                    break
                }
                if (!FlowPlanner.shouldContinue(runFlow.continueOnError, result.success)) {
                    logBus.warn("编排在失败步骤后停止: ${runFlow.name}")
                    break
                }
            }
        } finally {
            activeFlowId = null
            val succeeded = results.count { it.success }
            val allSucceeded = results.size == steps.size && succeeded == steps.size
            val summary = "${succeeded}/${steps.size} 成功"
            if (allSucceeded) {
                logBus.success("✔ 编排完成: ${runFlow.name}（$summary）")
            } else {
                logBus.warn("编排结束: ${runFlow.name}（$summary）")
            }
            dispatchSummary(runFlow, results.size, succeeded, steps.size, allSucceeded, startedAt)
            if (!manual) flowManager.onFinished(runFlow, allSucceeded)
        }
    }

    private fun dispatchSummary(
        flow: ScriptFlow,
        executed: Int,
        succeeded: Int,
        total: Int,
        allSucceeded: Boolean,
        startedAt: Long,
    ) {
        runCatching {
            notificationDispatcher.summary(
                flow = flow,
                executed = executed,
                succeeded = succeeded,
                total = total,
                allSucceeded = allSucceeded,
                durationMs = now() - startedAt,
            )
        }.onFailure {
            logBus.warn("编排结果通知派发失败：${it.message}")
        }
    }

    private suspend fun runStep(step: FlowStep, index: Int, total: Int): StepResult {
        val startedAt = now()
        val script = runCatching { scriptRepository.find(step.scriptId) }.getOrNull()
        if (script == null) {
            logBus.error("✘ 编排步骤 $index/$total 的脚本不存在: ${step.scriptName}")
            return StepResult(step, success = false, elapsedMs = 0L)
        }
        logBus.info("▶ 编排步骤 $index/$total: ${script.name}")
        val outcome = try {
            executionManager.runToCompletion(
                scriptId = script.id,
                scriptName = script.name,
                source = scriptRepository.read(script),
                trigger = RunTrigger.FLOW,
            )
        } catch (c: CancellationException) {
            // 取消必须向上传播，否则编排停止后仍会继续执行后续步骤。
            throw c
        } catch (error: Throwable) {
            logBus.error("✘ 编排步骤 $index/$total 异常: ${error.message}")
            return StepResult(
                step,
                success = false,
                elapsedMs = now() - startedAt,
            )
        }
        val elapsed = now() - startedAt
        return when (outcome) {
            ScriptRunOutcome.Busy -> {
                logBus.warn("编排步骤 $index/$total 未能启动（已有脚本运行）: ${script.name}")
                StepResult(step, success = false, elapsedMs = elapsed, busy = true)
            }

            is ScriptRunOutcome.Invalid -> {
                logBus.error("编排步骤 $index/$total 声明无效: ${outcome.reason}")
                StepResult(step, success = false, elapsedMs = elapsed)
            }

            ScriptRunOutcome.Stopped -> {
                logBus.warn("⏹ 步骤已停止: ${script.name}（${elapsed}ms）")
                StepResult(step, success = false, elapsedMs = elapsed, stopped = true)
            }

            is ScriptRunOutcome.Finished -> {
                if (outcome.success) {
                    logBus.success("✔ 步骤完成: ${script.name}（${elapsed}ms）")
                } else {
                    logBus.error("✘ 步骤失败: ${script.name}（${elapsed}ms）")
                }
                StepResult(step, outcome.success, elapsed)
            }
        }
    }

    private fun orderLabel(order: FlowOrder): String = when (order) {
        FlowOrder.SEQUENTIAL -> "顺序"
        FlowOrder.RANDOM -> "随机顺序"
    }

    private data class StepResult(
        val step: FlowStep,
        val success: Boolean,
        val elapsedMs: Long,
        val busy: Boolean = false,
        val stopped: Boolean = false,
    )

    private companion object {
        /** 事件触发去抖：同一条编排 60 秒内只执行一次。 */
        const val EVENT_DEBOUNCE_MS = 60_000L
    }
}
