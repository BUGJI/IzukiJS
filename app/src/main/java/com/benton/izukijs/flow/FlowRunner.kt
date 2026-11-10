package com.benton.izukijs.flow

import android.content.Context
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.data.ScriptRepository
import com.benton.izukijs.model.UnlockResult
import com.benton.izukijs.runtime.ScriptExecutionManager
import com.benton.izukijs.runtime.ScriptRunOutcome
import com.benton.izukijs.schedule.ScheduleEvent
import com.benton.izukijs.schedule.ScheduleKind
import com.benton.izukijs.schedule.TriggerConditions
import com.benton.izukijs.service.ScreenUnlockController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 脚本编排执行器：将步骤串行交给 [ScriptExecutionManager]，一步结束后再启动下一步。
 *
 * 执行期间不依赖 UI 生命周期。手动「立即执行」不会改动编排的触发时间或状态。
 */
class FlowRunner(
    context: Context,
    private val repository: FlowRepository,
    private val scriptRepository: ScriptRepository,
    private val flowManager: FlowManager,
    private val executionManager: ScriptExecutionManager,
    private val screenUnlock: ScreenUnlockController,
    private val foregroundProvider: () -> String?,
    private val logBus: LogBus,
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventMutex = Mutex()
    private val running = AtomicBoolean(false)
    private val triggerConditions = TriggerConditions(appContext, foregroundProvider)

    @Volatile
    private var activeFlowId: String? = null

    @Volatile
    private var stopRequested = false

    /** 立即执行一条编排；[manual] 为 true 时不修改触发调度。 */
    fun run(flow: ScriptFlow, manual: Boolean = false) {
        scope.launch {
            if (!running.compareAndSet(false, true)) {
                logBus.warn("编排跳过：已有编排正在运行（${flow.name}）")
                if (!manual) flowManager.onSkipped(flow)
                return@launch
            }
            try {
                runInternal(flow, manual)
            } finally {
                running.set(false)
            }
        }
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
                val now = System.currentTimeMillis()
                repository.list()
                    .filter { it.enabled && it.kind == ScheduleKind.EVENT && it.event == event }
                    .filter { now - it.lastTriggeredAtMillis >= EVENT_DEBOUNCE_MS }
                    .forEach { flow ->
                        val updated = flow.copy(lastTriggeredAtMillis = now)
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
            flow.copy(lastTriggeredAtMillis = System.currentTimeMillis()).also(repository::upsert)
        }
        activeFlowId = runFlow.id
        stopRequested = false
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
            if (!manual) flowManager.onFinished(runFlow, allSucceeded)
        }
    }

    private suspend fun runStep(step: FlowStep, index: Int, total: Int): StepResult {
        val startedAt = System.currentTimeMillis()
        val script = runCatching { scriptRepository.find(step.scriptId) }.getOrNull()
        if (script == null) {
            logBus.error("✘ 编排步骤 $index/$total 的脚本不存在: ${step.scriptName}")
            return StepResult(step, success = false, elapsedMs = 0L)
        }
        logBus.info("▶ 编排步骤 $index/$total: ${script.name}")
        val outcome = runCatching {
            executionManager.runToCompletion(
                scriptId = script.id,
                scriptName = script.name,
                source = scriptRepository.read(script),
            )
        }.getOrElse { error ->
            logBus.error("✘ 编排步骤 $index/$total 异常: ${error.message}")
            return StepResult(
                step,
                success = false,
                elapsedMs = System.currentTimeMillis() - startedAt,
            )
        }
        val elapsed = System.currentTimeMillis() - startedAt
        return when (outcome) {
            ScriptRunOutcome.Busy -> {
                logBus.warn("编排步骤 $index/$total 未能启动（已有脚本运行）: ${script.name}")
                StepResult(step, success = false, elapsedMs = elapsed, busy = true)
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
    )

    private companion object {
        /** 事件触发去抖：同一条编排 60 秒内只执行一次。 */
        const val EVENT_DEBOUNCE_MS = 60_000L
    }
}
