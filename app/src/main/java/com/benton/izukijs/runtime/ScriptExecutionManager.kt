package com.benton.izukijs.runtime

import android.content.Context
import com.benton.izukijs.R
import com.benton.izukijs.ai.AiConfigRepository
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.controller.record.RecordingSession
import com.benton.izukijs.data.ScriptEnvSession
import com.benton.izukijs.core.ScriptExitException
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.log.RunLogRegistry
import com.benton.izukijs.core.log.RunStatus
import com.benton.izukijs.core.perf.PerfMetrics
import com.benton.izukijs.core.runtime.RunControl
import com.benton.izukijs.developer.DeveloperTuning
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.RunMode
import com.benton.izukijs.runtime.api.DeviceApiBundle
import com.benton.izukijs.runtime.api.ImageApi
import com.benton.izukijs.core.notification.NotifyEvent
import com.benton.izukijs.core.notification.NotifyLevel
import com.benton.izukijs.core.notification.NotifyTemplate
import com.benton.izukijs.core.notification.ScriptNotificationCenter
import com.benton.izukijs.notification.NotificationDispatcher
import com.benton.izukijs.service.CaptureSettingsRepository
import com.benton.izukijs.service.ScreenCapture
import com.benton.izukijs.service.ScreenUnlockController
import com.benton.izukijs.service.ScriptForegroundService
import com.benton.izukijs.model.UnlockResult
import com.benton.izukijs.service.WakeLockController
import com.benton.izukijs.virtualscreen.VirtualScreenManager
import com.benton.izukijs.virtualscreen.VirtualScreenOptions
import com.benton.izukijs.virtualscreen.VirtualScreenSettings
import com.benton.izukijs.virtualscreen.VirtualScreenSettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 脚本执行管理器。执行不依赖 UI 生命周期：即使界面退到后台，前台服务也会保活。
 *
 * 同一时刻只有一个活动 run；忙时策略（拒绝 / 排队 / 终止）统一在这里处理，
 * 各入口只负责展示结果，不再各自判断 `running`。
 */
class ScriptExecutionManager(
    private val appContext: Context,
    private val controllers: ControllerManager,
    private val screenCapture: ScreenCapture,
    private val deviceApiBundle: DeviceApiBundle,
    private val nodeTreeProvider: (timeoutMs: Long) -> NodeTreeResult,
    private val logBus: LogBus,
    private val aiConfigRepository: AiConfigRepository,
    private val captureSettingsRepository: CaptureSettingsRepository,
    private val runtimeSettings: RuntimeSettingsRepository,
    private val httpSettingsRepository: HttpSettingsRepository,
    private val wakeLock: WakeLockController,
    private val screenUnlock: ScreenUnlockController,
    private val virtualScreen: VirtualScreenManager,
    private val virtualScreenSettings: VirtualScreenSettingsRepository,
    private val runPreparer: RunPreparer,
    private val moduleSourceProvider: (String) -> String?,
    private val notificationDispatcher: NotificationDispatcher,
    private val recordingSession: RecordingSession,
) : RunControl, ScriptRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _running = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _runningScript = MutableStateFlow<String?>(null)
    override val runningScript: StateFlow<String?> = _runningScript.asStateFlow()

    private val _runningScriptId = MutableStateFlow<String?>(null)
    val runningScriptId: StateFlow<String?> = _runningScriptId.asStateFlow()

    private val _runningRunId = MutableStateFlow<String?>(null)
    val runningRunId: StateFlow<String?> = _runningRunId.asStateFlow()

    private val _queuedScript = MutableStateFlow<String?>(null)
    val queuedScript: StateFlow<String?> = _queuedScript.asStateFlow()

    private val _queuedTerminating = MutableStateFlow(false)

    /** 排队项是否来自「终止当前脚本」。 */
    val queuedTerminating: StateFlow<Boolean> = _queuedTerminating.asStateFlow()

    @Volatile
    private var lastStartedRunId: String? = null

    fun lastRunId(): String? = lastStartedRunId

    /**
     * 运行互斥与停止请求状态机。启动、停止与结束清理都可能来自不同线程，
     * 由 [RunGate] 在同一把锁内完成「判断 + 更新」，否则会出现停止请求丢失
     * 或旧 run 清空新 run 状态的 ABA 问题。
     */
    private val gate = RunGate<JsEngine>()

    /**
     * 决策锁：把「判断运行位 / 入队 / 出队 / 强制放弃」收敛到同一把锁。
     *
     * 锁顺序固定为 [decisionLock] -> [gate] 内部锁；不要在持有 gate 锁时再取 decisionLock。
     */
    private val decisionLock = Any()

    private val pendingRuns = RunQueue<PendingRun>()

    private var terminateWatchdog: Job? = null

    /** 活动 run 句柄；强制放弃时据此标记旧 run，避免把终止误判成成功退出。 */
    private val activeSessions = ConcurrentHashMap<String, RunSession>()

    fun run(
        scriptId: String,
        scriptName: String,
        source: String,
        values: Map<String, String> = emptyMap(),
        onFinished: ((Boolean) -> Unit)? = null,
        onCompletion: ((RunCompletion) -> Unit)? = null,
        trigger: RunTrigger = RunTrigger.MANUAL,
        overrides: RunOverrides = RunOverrides(),
    ): RunStartResult {
        val needs = ScriptNeeds.parse(source)
        if (needs != null) {
            val unknown = needs - ScriptNeeds.KNOWN
            if (unknown.isNotEmpty()) {
                logBus.error("未知的 @need 能力: ${unknown.sorted().joinToString(", ")}")
                return RunStartResult.Rejected(null)
            }
        }
        val request = PendingRun(
            scriptId = scriptId,
            scriptName = scriptName,
            source = source,
            values = values,
            needs = needs,
            onFinished = onFinished,
            onCompletion = onCompletion,
            trigger = trigger,
            terminate = false,
            overrides = overrides,
        )
        return submit(request)
    }

    override fun runScheduled(
        scriptId: String,
        scriptName: String,
        source: String,
        trigger: RunTrigger,
        onFinished: (Boolean) -> Unit,
    ): RunStartResult = run(
        scriptId = scriptId,
        scriptName = scriptName,
        source = source,
        onFinished = onFinished,
        trigger = trigger,
    )

    override suspend fun runToCompletion(
        scriptId: String,
        scriptName: String,
        source: String,
        trigger: RunTrigger,
    ): ScriptRunOutcome = runToCompletion(
        scriptId = scriptId,
        scriptName = scriptName,
        source = source,
        values = emptyMap(),
        trigger = trigger,
    )

    /**
     * 启动脚本并等待其真正结束。供脚本编排等需要「完成一步再执行下一步」的调用方使用。
     *
     * 排队 / 终止策略下会等当前脚本结束后再启动；只有策略为拒绝时才返回 [ScriptRunOutcome.Busy]。
     */
    suspend fun runToCompletion(
        scriptId: String,
        scriptName: String,
        source: String,
        values: Map<String, String> = emptyMap(),
        trigger: RunTrigger = RunTrigger.MANUAL,
    ): ScriptRunOutcome {
        val unknownNeeds = ScriptNeeds.parse(source)
            ?.minus(ScriptNeeds.KNOWN)
            .orEmpty()
        if (unknownNeeds.isNotEmpty()) {
            return ScriptRunOutcome.Invalid(
                "未知 @need 能力: ${unknownNeeds.sorted().joinToString(", ")}",
            )
        }
        val finished = CompletableDeferred<RunCompletion>()
        val result = run(
            scriptId = scriptId,
            scriptName = scriptName,
            source = source,
            values = values,
            onCompletion = { completion -> finished.complete(completion) },
            trigger = trigger,
        )
        if (!result.accepted) return ScriptRunOutcome.Busy
        val completion = finished.await()
        if (completion.cancelledBeforeStart) return ScriptRunOutcome.Stopped
        if (completion.runId.isNotEmpty()) {
            // 等本 run 真正让出运行位；可能已直接提升给下一条排队脚本，不能等 running=false。
            runningRunId.first { it != completion.runId }
        } else {
            running.first { !it }
        }
        return if (completion.stopped) {
            ScriptRunOutcome.Stopped
        } else {
            ScriptRunOutcome.Finished(completion.success)
        }
    }

    /**
     * 统一的启动 / 排队决策。所有入口共用，避免不同触发来源对 `running` 的判断漂移。
     */
    private fun submit(request: PendingRun): RunStartResult {
        val policy = runtimeSettings.current().busyPolicy
        val displaced = mutableListOf<PendingRun>()
        var terminateCurrent = false
        val result = synchronized(decisionLock) {
            var started: RunStartResult? = null
            if (!gate.isRunning()) {
                val session = RunSession(RunLogRegistry.newRunId())
                if (gate.tryStart(session.runId)) {
                    launchRun(session, request)
                    started = RunStartResult.Started
                }
            }
            started ?: when (policy) {
                RunBusyPolicy.REJECT -> RunStartResult.Rejected(_runningScript.value)

                RunBusyPolicy.QUEUE -> {
                    terminateWatchdog?.cancel()
                    terminateWatchdog = null
                    pendingRuns.offer(request.copy(terminate = false))?.let(displaced::add)
                    publishQueued(request, terminate = false)
                    RunStartResult.Queued
                }

                RunBusyPolicy.TERMINATE -> {
                    terminateWatchdog?.cancel()
                    terminateWatchdog = null
                    pendingRuns.offer(request.copy(terminate = true))?.let(displaced::add)
                    publishQueued(request, terminate = true)
                    terminateCurrent = true
                    RunStartResult.Terminating(_runningScript.value)
                }
            }
        }
        // 回调必须放在锁外，避免调用方在回调里再次启动 / 停止造成自锁。
        displaced.forEach { cancelPending(it, "已被新的启动请求覆盖") }
        if (terminateCurrent) requestTerminate()
        return result
    }

    private fun publishQueued(request: PendingRun, terminate: Boolean) {
        _queuedScript.value = request.scriptName
        _queuedTerminating.value = terminate
    }

    private fun clearQueuedState() {
        _queuedScript.value = null
        _queuedTerminating.value = false
    }

    /** 真正占用运行位后发布状态并启动准备协程。调用方必须已持有 [decisionLock]。 */
    private fun launchRun(session: RunSession, request: PendingRun) {
        lastStartedRunId = session.runId
        activeSessions[session.runId] = session
        RunLogRegistry.begin(session.runId, request.scriptId, request.scriptName)
        // 同步发布运行身份：前台服务清理按 runId 判断归属，准备阶段也不能出现空窗。
        _running.value = true
        _runningScript.value = request.scriptName
        _runningScriptId.value = request.scriptId
        _runningRunId.value = session.runId
        clearQueuedState()
        // 通知归属在真正占用运行位时就切换；准备阶段失败或被放弃时也能按 runId 正确清理。
        ScriptNotificationCenter.begin(session.runId, request.scriptName)
        PerfMetrics.count("script.run")
        PerfMetrics.setGauge("script.running", 1L)

        scope.launch {
            withContext(RunLogRegistry.asContextElement(session.runId)) {
                if (!gate.isActive(session.runId)) {
                    finishAbandonedBeforeStart(session, request)
                    return@withContext
                }
                // 终止超时接管时，旧脚本可能仍持有脚本录制；在启动准备前释放，
                // 避免把文件收尾 I/O 放进 decisionLock。此时新脚本尚未执行，不会抢录制。
                if (request.releasesScriptRecording) {
                    runCatching { recordingSession.stopScriptRecording() }
                }
                val prepared = try {
                    PerfMetrics.measure("script.prepare") {
                        runPreparer.prepare(
                            runId = session.runId,
                            scriptId = request.scriptId,
                            scriptName = request.scriptName,
                            source = request.source,
                            values = request.values,
                            needs = request.needs,
                            onStarted = { name, id, startedRunId ->
                                // 准备阶段可能已因终止超时被放弃；不能再覆盖新 run 的展示状态。
                                if (gate.isActive(startedRunId)) {
                                    _runningScript.value = name
                                    _runningScriptId.value = id
                                    _runningRunId.value = startedRunId
                                }
                            },
                            isActive = { gate.isActive(session.runId) },
                            runModeOverride = request.overrides.runMode,
                        )
                    }
                } catch (t: Throwable) {
                    if (!gate.isActive(session.runId)) {
                        // 准备阶段已被强制放弃：不要用失败覆盖新 run 的状态。
                        finishAbandonedBeforeStart(session, request)
                    } else {
                        logBus.error("✘ 启动脚本失败: ${t.message}")
                        rollbackStart(session, request, t)
                    }
                    return@withContext
                }
                if (!gate.isActive(session.runId)) {
                    finishAbandonedBeforeStart(session, request)
                    return@withContext
                }
                executeRun(
                    session = session,
                    request = request,
                    prepared = prepared,
                )
            }
        }
    }

    private suspend fun executeRun(
        session: RunSession,
        request: PendingRun,
        prepared: PreparedRun,
    ) {
        val startedAt = System.currentTimeMillis()
        var failure: Throwable? = null
        var virtualOwned = false
        val settings = runtimeSettings.current()
        if (!gate.isActive(session.runId)) {
            finishAbandonedBeforeStart(session, request)
            return
        }
        try {
            val autoUnlock = request.overrides.autoUnlock ?: true
            if (autoUnlock && screenUnlock.isEnabled()) {
                val result = screenUnlock.ensureUnlocked()
                if (result != UnlockResult.OK &&
                    result != UnlockResult.ALREADY_UNLOCKED &&
                    result != UnlockResult.DISABLED
                ) {
                    logBus.warn("自动解锁未完成: $result")
                }
            }
            if (settings.keepAwake) session.acquireWakeLock()
            if (request.overrides.wakeScreen ?: settings.wakeScreen) {
                runCatching { controllers.controllerFor(Capability.KEY)?.pressKey(KEYCODE_WAKEUP) }
            }
            if (!gate.isActive(session.runId)) {
                finishAbandonedBeforeStart(session, request)
                return
            }
            var effectiveRunMode = prepared.plannedRunMode
            if (effectiveRunMode == RunMode.BACKGROUND) {
                val hinted = VirtualScreenStartHint.parse(
                    request.source,
                    prepared.virtualSettings.resolution,
                )
                if (hinted != null) {
                    logBus.debug(
                        "按脚本 virtualScreen.start 参数预创建虚拟屏" +
                            "（${hinted.width}x${hinted.height}@${hinted.dpi}）",
                    )
                }
                if (request.overrides.reuseVirtualScreen && virtualScreen.isActive()) {
                    // 用户手动启动的虚拟屏不应被本次脚本接管 / 收尾停止；脚本仍可用
                    // virtualScreen.stop() 显式释放（该接口允许停止无主实例）。
                    logBus.debug("复用已运行的虚拟屏（display=${virtualScreen.displayId()}），本次运行不持有归属")
                } else {
                    val started = virtualScreen.start(
                        VirtualScreenOptions(hinted ?: prepared.virtualSettings.resolution),
                        ownerRunId = session.runId,
                        // 只有仍持有运行位的 run 才能接管旧实例，避免终止超时后的旧脚本抢回。
                        isRequesterCurrent = { gate.isActive(session.runId) },
                    )
                    if (started) {
                        virtualOwned = true
                    } else {
                        logBus.warn("后台虚拟屏启动失败，本次回退前台模式")
                        effectiveRunMode = RunMode.FOREGROUND
                    }
                }
            }
            virtualScreen.resetTarget(effectiveRunMode)
            logBus.info(
                "运行模式: " +
                    if (effectiveRunMode == RunMode.BACKGROUND) {
                        "后台虚拟屏（display=${virtualScreen.displayId()}）"
                    } else {
                        "前台"
                    },
            )
            if (effectiveRunMode == RunMode.BACKGROUND &&
                request.needs?.contains("selector") == true
            ) {
                logBus.warn("@need selector 在后台虚拟屏上不可用，请改用 capture / ocr / 坐标输入")
            }
            // 并行预热视觉能力，降低脚本首次找图 / OCR 的冷启动延迟。
            if (request.needs == null ||
                "images" in request.needs ||
                "pipeline" in request.needs
            ) {
                ImageApi.prewarm()
            }
            if (request.needs == null || "ocr" in request.needs || "pipeline" in request.needs) {
                deviceApiBundle.ocrApi.prewarm()
            }
            val jsEngine = JsEngine(
                appContext,
                controllers,
                screenCapture,
                deviceApiBundle,
                nodeTreeProvider,
                logBus,
                aiConfigRepository,
                captureSettingsRepository,
                prepared.scriptEnv,
                runtimeSettings,
                httpSettingsRepository,
                moduleSourceProvider,
                virtualScreen,
                effectiveRunMode,
                scriptId = request.scriptId,
                scriptName = request.scriptName,
                runId = session.runId,
                pushDispatcher = notificationDispatcher,
                recordingSession = recordingSession,
                isRunActive = { gate.isActive(session.runId) },
            )
            // 发布引擎与检查停止请求必须在同一把锁下完成：否则 requestStop 可能看到
            // engine 仍为 null 只置了 stopRequested，而这里已经检查过该标志，导致停止丢失。
            session.engine = jsEngine
            val stopAlreadyRequested = gate.attachEngine(session.runId, jsEngine)
            if (stopAlreadyRequested) {
                logBus.info("… 收到停止请求，正在终止脚本")
                jsEngine.requestExit()
            }
            jsEngine.execute(
                source = request.source,
                fileName = request.scriptName,
                timeoutMs = settings.scriptTimeoutMinutes * 60_000L,
                needs = request.needs,
            )
        } catch (t: Throwable) {
            failure = t
        } finally {
            try {
                val timedOut = !session.abandoned && session.engine?.timedOut == true
                // 用户停止与脚本主动 exit() 都会置 exitRequested，必须用 stopRequested 区分：
                // 用户停止不能再被当作成功，否则脚本编排会继续执行下一步。
                // 被强制放弃的旧 run 同样按「停止」收尾，不能因为 exitRequested 记成成功退出。
                val userStopped = session.abandoned || gate.isStopRequested()
                val exited = !userStopped &&
                    (session.engine?.exitRequested == true || isExitThrowable(failure))
                clearEngine(session)
                if (virtualScreen.isActive()) {
                    if (!virtualOwned) {
                        logBus.debug("虚拟屏属于用户或其他运行，本次收尾不停止")
                    } else if (virtualScreenSettings.current().autoStopAfterRun) {
                        val stopped = runCatching {
                            virtualScreen.stopIfOwned(
                                session.runId,
                                VirtualScreenManager.STOP_RUN_FINISHED,
                            )
                        }.getOrDefault(false)
                        if (!stopped) {
                            logBus.debug("虚拟屏已由新的脚本接管，本次收尾不停止")
                        }
                    } else {
                        logBus.info("后台虚拟屏保持运行，可在运行状态页手动停止或由脚本销毁")
                    }
                }
                when {
                    timedOut -> logBus.error(
                        "⏱ 脚本运行超时（${settings.scriptTimeoutMinutes} 分钟），已中断",
                    )

                    userStopped -> logBus.warn("■ 脚本已停止")
                    exited -> logBus.success("■ 脚本已退出")
                    failure != null -> logBus.error("✘ 运行出错: ${failure.describeForLog()}")
                    else -> logBus.success("✔ 脚本执行完成")
                }
                val resolved = RunStatusResolver.resolve(
                    timedOut = timedOut,
                    userStopped = userStopped,
                    exited = exited,
                    failed = failure != null,
                )
                val status = resolved.status
                val error = failure?.let {
                    it.stackTraceToString().take(DeveloperTuning.current.maxRunErrorChars)
                }
                RunLogRegistry.finish(session.runId, status, error)
                dispatchRunResult(
                    runId = session.runId,
                    scriptId = request.scriptId,
                    scriptName = request.scriptName,
                    trigger = request.trigger,
                    status = status,
                    durationMs = System.currentTimeMillis() - startedAt,
                )
                when (status) {
                    RunStatus.SUCCEEDED -> PerfMetrics.count("script.succeeded")
                    RunStatus.FAILED -> PerfMetrics.count("script.failed")
                    RunStatus.TIMEOUT -> PerfMetrics.count("script.timeout")
                    RunStatus.STOPPED -> PerfMetrics.count("script.stopped")
                    RunStatus.RUNNING -> Unit
                }
                val success = resolved.success
                val completion = RunCompletion(
                    success = success,
                    stopped = userStopped,
                    status = status,
                    runId = session.runId,
                )
                runCatching { request.onFinished?.invoke(success) }.onFailure {
                    logBus.warn("运行结束回调失败：${it.message}")
                }
                runCatching { request.onCompletion?.invoke(completion) }.onFailure {
                    logBus.warn("运行完成回调失败：${it.message}")
                }
            } finally {
                // 无论日志 / 停止流程是否抛异常，都必须释放资源与运行标记。
                session.releaseWakeLock()
                // 有排队项时保持前台服务存活，避免后台进程在交接窗口被回收。
                if (resetRunningState(session) == ResetOutcome.NO_PENDING) {
                    runCatching { ScriptForegroundService.stop(appContext, session.runId) }
                    ScriptNotificationCenter.clear(session.runId)
                }
            }
        }
    }

    /**
     * 本次 run 在准备 / 执行前已被强制放弃：不再产生新的全局副作用，
     * 只结束日志与回调。前台服务 / 通知已归属新 run，不能在这里清理。
     */
    private fun finishAbandonedBeforeStart(session: RunSession, request: PendingRun) {
        logBus.warn("… 脚本启动被新的运行请求取代: ${request.scriptName}")
        RunLogRegistry.finish(session.runId, RunStatus.STOPPED)
        dispatchRunResult(
            runId = session.runId,
            scriptId = request.scriptId,
            scriptName = request.scriptName,
            trigger = request.trigger,
            status = RunStatus.STOPPED,
            durationMs = 0L,
        )
        val completion = RunCompletion(
            success = false,
            stopped = true,
            status = RunStatus.STOPPED,
            runId = session.runId,
        )
        runCatching { request.onFinished?.invoke(false) }.onFailure {
            logBus.warn("运行结束回调失败：${it.message}")
        }
        runCatching { request.onCompletion?.invoke(completion) }.onFailure {
            logBus.warn("运行完成回调失败：${it.message}")
        }
        session.releaseWakeLock()
        resetRunningState(session)
    }

    /** 预启动失败时回滚：结束 run 记录、释放锁、重置状态并停掉已拉起的前台服务。 */
    private fun rollbackStart(session: RunSession, request: PendingRun, error: Throwable) {
        val message = error.stackTraceToString().take(DeveloperTuning.current.maxRunErrorChars)
        RunLogRegistry.finish(session.runId, RunStatus.FAILED, message)
        dispatchRunResult(
            runId = session.runId,
            scriptId = request.scriptId,
            scriptName = request.scriptName,
            trigger = request.trigger,
            status = RunStatus.FAILED,
            durationMs = 0L,
            error = message,
        )
        PerfMetrics.count("script.failed")
        session.releaseWakeLock()
        val reset = resetRunningState(session)
        if (reset == ResetOutcome.NO_PENDING) {
            runCatching { ScriptForegroundService.stop(appContext, session.runId) }
            ScriptNotificationCenter.clear(session.runId)
        }
        runCatching { request.onFinished?.invoke(false) }.onFailure {
            logBus.warn("运行结束回调失败：${it.message}")
        }
        runCatching {
            request.onCompletion?.invoke(
                RunCompletion(
                    success = false,
                    stopped = false,
                    status = RunStatus.FAILED,
                    runId = session.runId,
                ),
            )
        }.onFailure {
            logBus.warn("运行完成回调失败：${it.message}")
        }
    }

    /** 通知排队项的所有者：本次不会启动，避免编排 / 定时任务无限等待。 */
    private fun cancelPending(pending: PendingRun, reason: String) {
        logBus.info("… 已取消排队: ${pending.scriptName}（$reason）")
        runCatching { pending.onFinished?.invoke(false) }.onFailure {
            logBus.warn("运行结束回调失败：${it.message}")
        }
        runCatching {
            pending.onCompletion?.invoke(
                RunCompletion(
                    success = false,
                    stopped = true,
                    status = RunStatus.STOPPED,
                    cancelledBeforeStart = true,
                ),
            )
        }.onFailure {
            logBus.warn("运行完成回调失败：${it.message}")
        }
    }

    /** 把运行结果转换为统一通知事件；实际发送由 [NotificationDispatcher] 异步完成。 */
    private fun dispatchRunResult(
        runId: String,
        scriptId: String,
        scriptName: String,
        trigger: RunTrigger,
        status: RunStatus,
        durationMs: Long,
        error: String? = null,
    ) {
        val level = when (status) {
            RunStatus.SUCCEEDED -> NotifyLevel.SUCCESS
            RunStatus.STOPPED -> NotifyLevel.INFO
            RunStatus.TIMEOUT, RunStatus.FAILED -> NotifyLevel.ERROR
            RunStatus.RUNNING -> NotifyLevel.INFO
        }
        val titleRes = when (status) {
            RunStatus.SUCCEEDED -> R.string.notif_run_success_title
            RunStatus.STOPPED -> R.string.notif_run_stopped_title
            RunStatus.TIMEOUT -> R.string.notif_run_timeout_title
            RunStatus.FAILED -> R.string.notif_run_failed_title
            RunStatus.RUNNING -> R.string.notif_run_success_title
        }
        val statusKey = status.name.lowercase()
        val event = NotifyEvent(
            level = level,
            kind = trigger.kind,
            title = appContext.getString(titleRes, scriptName),
            text = appContext.getString(
                R.string.notif_run_text,
                NotifyTemplate.formatDuration(durationMs),
            ),
            source = trigger.source,
            scriptId = scriptId,
            scriptName = scriptName,
            runId = runId,
            runStatus = statusKey,
            durationMs = durationMs,
            errorTail = error ?: RunLogRegistry.find(runId)?.error,
            tags = setOf(trigger.kind.key, statusKey),
            data = mapOf(
                "status" to statusKey,
                "trigger" to trigger.name.lowercase(),
            ),
        )
        runCatching { notificationDispatcher.dispatch(event) }.onFailure {
            logBus.warn("运行结果通知派发失败：${it.message}")
        }
    }

    /**
     * 关闭本次 run 的引擎。仅当 [session] 仍是当前活动 run 时才清除共享字段，
     * 避免旧 run 的收尾把新 run 的引擎置空（ABA）。
     */
    private fun clearEngine(session: RunSession) {
        val toClose = session.engine
        session.engine = null
        // 只有仍是活动 run 时才清空共享引擎；旧 run 的收尾不会碰新 run 的引擎。
        gate.detachEngine(session.runId)
        toClose?.let { runCatching { it.close() } }
    }

    private enum class ResetOutcome {
        /** 该 run 已不是活动 run（被强制放弃）；不得触碰新 run 的资源。 */
        INACTIVE,

        /** 已重置且没有排队项；调用方负责停止前台服务 / 清空通知。 */
        NO_PENDING,

        /** 已把运行位直接交给排队项；前台服务和通知由新 run 接管。 */
        PROMOTED,
    }

    /**
     * 重置运行状态，并尝试把运行位直接交给排队项。
     *
     * 提升与重置在同一把锁内完成，避免「reset 后、提升前」被新请求插队。
     */
    private fun resetRunningState(session: RunSession): ResetOutcome = synchronized(decisionLock) {
        activeSessions.remove(session.runId)
        // 已被新 run 取代的旧 run 不能动新 run 的队列或终止看门狗。
        if (!gate.isActive(session.runId)) return ResetOutcome.INACTIVE

        terminateWatchdog?.cancel()
        terminateWatchdog = null

        val next = pendingRuns.take()
        if (next == null) {
            if (!gate.reset(session.runId)) return ResetOutcome.INACTIVE
            clearRunningState()
            clearQueuedState()
            return ResetOutcome.NO_PENDING
        }

        // 原子转交运行位：不再「先 reset 再 tryStart」，避免两步之间出现无人持有运行位的窗口。
        val nextSession = RunSession(RunLogRegistry.newRunId())
        if (!gate.replaceActive(session.runId, nextSession.runId)) {
            // decisionLock 内运行位只可能仍是 session；保守恢复队列，绝不静默丢弃。
            logBus.error("排队提升失败：运行位已变化，保留待执行项")
            pendingRuns.offer(next)
            publishQueued(next, next.terminate)
            return ResetOutcome.INACTIVE
        }
        clearRunningState()
        clearQueuedState()
        launchRun(nextSession, next)
        ResetOutcome.PROMOTED
    }

    /** 清空当前 run 的展示状态；调用方必须持有 [decisionLock]。 */
    private fun clearRunningState() {
        _running.value = false
        _runningScript.value = null
        _runningScriptId.value = null
        _runningRunId.value = null
        PerfMetrics.setGauge("script.running", 0L)
    }

    /** 请求停止当前脚本；通过 QuickJS 中断回调，纯计算死循环也能被打断。 */
    override fun requestStop() {
        // 清空排队与解析停止动作必须在同一把锁内完成，否则旧 run 收尾后新 run 可能刚启动，
        // 随后的 resolveStop 会误停新 run。
        val (canceled, action) = synchronized(decisionLock) {
            terminateWatchdog?.cancel()
            terminateWatchdog = null
            val pending = pendingRuns.clear().also { clearQueuedState() }
            pending to gate.resolveStop()
        }
        canceled?.let { cancelPending(it, "用户停止") }
        when (action) {
            is RunGate.StopAction.StopEngine -> {
                logBus.info("… 正在停止脚本")
                action.engine.requestExit()
            }

            RunGate.StopAction.WaitStart -> logBus.info("… 正在停止脚本（等待启动完成）")

            RunGate.StopAction.None -> {
                if (canceled == null) logBus.warn("当前没有正在运行的脚本")
            }
        }
    }

    /** 取消排队项（不停止当前脚本）。返回是否确实取消了一项。 */
    fun cancelQueued(): Boolean {
        val canceled = synchronized(decisionLock) {
            terminateWatchdog?.cancel()
            terminateWatchdog = null
            pendingRuns.clear().also { clearQueuedState() }
        }
        canceled?.let { cancelPending(it, "用户取消排队") } ?: return false
        return true
    }

    /**
     * 终止当前脚本；15 秒内仍未让出运行位则强制放弃并启动排队项。
     *
     * 强制放弃只解除归属，不跨线程关闭旧引擎；旧 run 在阻塞调用返回后自行退出。
     */
    private fun requestTerminate() {
        // 与提升排队项共用 decisionLock：若旧 run 已结束并提升，pending 不再存在，
        // 此时不能再 resolveStop，否则会误停刚启动的新 run。
        val action = synchronized(decisionLock) {
            val pending = pendingRuns.peek()
            if (pending == null || !pending.terminate) {
                null
            } else {
                gate.resolveStop()
            }
        } ?: return
        when (action) {
            is RunGate.StopAction.StopEngine -> {
                logBus.info("… 正在终止当前脚本")
                action.engine.requestExit()
            }

            RunGate.StopAction.WaitStart -> logBus.info("… 正在终止当前脚本（等待启动完成）")

            RunGate.StopAction.None -> Unit
        }
        startTerminateWatchdog()
    }

    private fun startTerminateWatchdog() {
        terminateWatchdog?.cancel()
        terminateWatchdog = scope.launch {
            delay(TERMINATE_WAIT_MS)
            val promoted = synchronized(decisionLock) {
                val pending = pendingRuns.peek()
                val oldRunId = gate.activeRunId()
                if (pending == null || !pending.terminate || oldRunId == null) {
                    null
                } else {
                    pendingRuns.clear()
                    clearQueuedState()
                    terminateWatchdog = null
                    val nextSession = RunSession(RunLogRegistry.newRunId())
                    if (!gate.replaceActive(oldRunId, nextSession.runId)) {
                        pendingRuns.offer(pending)
                        publishQueued(pending, pending.terminate)
                        null
                    } else {
                        activeSessions[oldRunId]?.abandoned = true
                        // 录制释放放到新 run 的准备协程里，避免文件收尾 I/O 占用 decisionLock。
                        val takeover = pending.copy(releasesScriptRecording = true)
                        launchRun(nextSession, takeover)
                        takeover
                    }
                }
            } ?: return@launch

            logBus.warn(
                "⚠ 当前脚本 ${TERMINATE_WAIT_MS / 1000} 秒内未退出，" +
                    "已放弃等待并启动: ${promoted.scriptName}",
            )
        }
    }

    private fun isExitThrowable(t: Throwable?): Boolean {
        var current = t
        var guard = 0
        while (current != null && guard < 10) {
            if (current is ScriptExitException) return true
            if (current.message?.contains(ScriptExitException.MARKER) == true) return true
            current = current.cause
            guard++
        }
        return false
    }

    /** 一次待启动请求；源码与参数在入队时快照，避免等待期间脚本被编辑产生歧义。 */
    private data class PendingRun(
        val scriptId: String,
        val scriptName: String,
        val source: String,
        val values: Map<String, String>,
        val needs: Set<String>?,
        val onFinished: ((Boolean) -> Unit)?,
        val onCompletion: ((RunCompletion) -> Unit)?,
        val trigger: RunTrigger,
        val terminate: Boolean,
        /** 终止超时接管时置 true：启动前释放旧脚本仍持有的录制。 */
        val releasesScriptRecording: Boolean = false,
        /** MCP 等调用方对本次运行的显式覆盖。 */
        val overrides: RunOverrides = RunOverrides(),
    )

    /** 一次运行的资源与状态句柄；所有释放操作都幂等，供异常路径复用。 */
    private inner class RunSession(val runId: String) {

        @Volatile
        var engine: JsEngine? = null

        /** 已被新 run 强制取代；收尾必须按停止处理且不得清理新 run 的资源。 */
        @Volatile
        var abandoned: Boolean = false

        @Volatile
        private var wakeLockHeld: Boolean = false

        @Synchronized
        fun acquireWakeLock() {
            if (wakeLockHeld) return
            wakeLock.acquire()
            wakeLockHeld = true
        }

        @Synchronized
        fun releaseWakeLock() {
            if (!wakeLockHeld) return
            wakeLockHeld = false
            wakeLock.release()
        }

    }

    private companion object {
        /** KEYCODE_WAKEUP：点亮屏幕。 */
        const val KEYCODE_WAKEUP = 224

        /** 终止策略等待旧脚本退出的上限；超过后强行放弃运行位。 */
        const val TERMINATE_WAIT_MS = 15_000L
    }
}
