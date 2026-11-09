package com.benton.izukijs.runtime

import android.content.Context
import com.benton.izukijs.ai.AiConfigRepository
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.data.ScriptEnvSession
import com.benton.izukijs.core.ScriptExitException
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.log.RunLogRegistry
import com.benton.izukijs.core.log.RunStatus
import com.benton.izukijs.core.perf.PerfMetrics
import com.benton.izukijs.core.runtime.RunControl
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.RunMode
import com.benton.izukijs.runtime.api.DeviceApiBundle
import com.benton.izukijs.runtime.api.ImageApi
import com.benton.izukijs.core.notification.ScriptNotificationCenter
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 脚本执行管理器。执行不依赖 UI 生命周期：即使界面退到后台，前台服务也会保活。
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
) : RunControl {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _running = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _runningScript = MutableStateFlow<String?>(null)
    override val runningScript: StateFlow<String?> = _runningScript.asStateFlow()

    private val _runningScriptId = MutableStateFlow<String?>(null)
    val runningScriptId: StateFlow<String?> = _runningScriptId.asStateFlow()

    private val _runningRunId = MutableStateFlow<String?>(null)
    val runningRunId: StateFlow<String?> = _runningRunId.asStateFlow()

    @Volatile
    private var lastStartedRunId: String? = null

    fun lastRunId(): String? = lastStartedRunId

    @Volatile
    private var engine: JsEngine? = null

    @Volatile
    private var activeSession: RunSession? = null

    fun run(
        scriptId: String,
        scriptName: String,
        source: String,
        values: Map<String, String> = emptyMap(),
        onFinished: ((Boolean) -> Unit)? = null,
    ): Boolean {
        val needs = ScriptNeeds.parse(source)
        if (needs != null) {
            val unknown = needs - ScriptNeeds.KNOWN
            if (unknown.isNotEmpty()) {
                logBus.error("未知的 @need 能力: ${unknown.sorted().joinToString(", ")}")
                return false
            }
        }
        if (!_running.compareAndSet(expect = false, update = true)) {
            logBus.warn("已有脚本正在运行，请先停止")
            return false
        }
        val runId = RunLogRegistry.newRunId()
        lastStartedRunId = runId
        RunLogRegistry.begin(runId, scriptId, scriptName)
        val session = RunSession(runId)
        activeSession = session

        // 参数解析、运行历史落盘、通知 / 悬浮窗启动等放到后台，避免从界面点运行时阻塞主线程。
        // 预启动阶段的任何异常都必须回滚，否则 _running 会永久卡在 true。
        scope.launch {
            withContext(RunLogRegistry.asContextElement(runId)) {
                val prepared = try {
                    PerfMetrics.measure("script.prepare") {
                        runPreparer.prepare(
                            runId = runId,
                            scriptId = scriptId,
                            scriptName = scriptName,
                            source = source,
                            values = values,
                            needs = needs,
                            onStarted = { name, id, startedRunId ->
                                _runningScript.value = name
                                _runningScriptId.value = id
                                _runningRunId.value = startedRunId
                            },
                        )
                    }
                } catch (t: Throwable) {
                    logBus.error("✘ 启动脚本失败: ${t.message}")
                    rollbackStart(session, t, onFinished)
                    return@withContext
                }
                executeRun(
                    session = session,
                    needs = needs,
                    source = source,
                    scriptName = scriptName,
                    plannedRunMode = prepared.plannedRunMode,
                    virtualSettings = prepared.virtualSettings,
                    scriptEnv = prepared.scriptEnv,
                    onFinished = onFinished,
                )
            }
        }
        return true
    }

    private suspend fun executeRun(
        session: RunSession,
        needs: Set<String>?,
        source: String,
        scriptName: String,
        plannedRunMode: RunMode,
        virtualSettings: VirtualScreenSettings,
        scriptEnv: ScriptEnvSession,
        onFinished: ((Boolean) -> Unit)?,
    ) {
        var failure: Throwable? = null
        val settings = runtimeSettings.current()
        try {
            if (screenUnlock.isEnabled()) {
                val result = screenUnlock.ensureUnlocked()
                if (result != UnlockResult.OK &&
                    result != UnlockResult.ALREADY_UNLOCKED &&
                    result != UnlockResult.DISABLED
                ) {
                    logBus.warn("自动解锁未完成: $result")
                }
            }
            if (settings.keepAwake) session.acquireWakeLock()
            if (settings.wakeScreen) {
                runCatching { controllers.controllerFor(Capability.KEY)?.pressKey(KEYCODE_WAKEUP) }
            }
            session.startGenerationBefore = virtualScreen.startGeneration()
            var effectiveRunMode = plannedRunMode
            if (effectiveRunMode == RunMode.BACKGROUND) {
                val started = virtualScreen.start(
                    VirtualScreenOptions(virtualSettings.resolution),
                )
                if (!started) {
                    logBus.warn("后台虚拟屏启动失败，本次回退前台模式")
                    effectiveRunMode = RunMode.FOREGROUND
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
            if (effectiveRunMode == RunMode.BACKGROUND && needs?.contains("selector") == true) {
                logBus.warn("@need selector 在后台虚拟屏上不可用，请改用 capture / ocr / 坐标输入")
            }
            // 并行预热视觉能力，降低脚本首次找图 / OCR 的冷启动延迟。
            if (needs == null || "images" in needs) ImageApi.prewarm()
            if (needs == null || "ocr" in needs) deviceApiBundle.ocrApi.prewarm()
            val jsEngine = JsEngine(
                appContext,
                controllers,
                screenCapture,
                deviceApiBundle,
                nodeTreeProvider,
                logBus,
                aiConfigRepository,
                captureSettingsRepository,
                scriptEnv,
                runtimeSettings,
                httpSettingsRepository,
                moduleSourceProvider,
                virtualScreen,
                effectiveRunMode,
            ).also {
                session.engine = it
                engine = it
            }
            if (session.stopRequested) {
                logBus.info("… 收到停止请求，正在终止脚本")
                jsEngine.requestExit()
            }
            jsEngine.execute(
                source = source,
                fileName = scriptName,
                timeoutMs = settings.scriptTimeoutMinutes * 60_000L,
                needs = needs,
            )
        } catch (t: Throwable) {
            failure = t
        } finally {
            try {
                val timedOut = session.engine?.timedOut == true
                val exited = session.engine?.exitRequested == true || isExitThrowable(failure)
                session.closeEngine()
                engine = null
                val startedThisRun = virtualScreen.startGeneration() > session.startGenerationBefore
                if (virtualScreen.isActive()) {
                    if (virtualScreenSettings.current().autoStopAfterRun && startedThisRun) {
                        runCatching { virtualScreen.stop(VirtualScreenManager.STOP_RUN_FINISHED) }
                    } else {
                        logBus.info("后台虚拟屏保持运行，可在运行状态页手动停止或由脚本销毁")
                    }
                }
                when {
                    timedOut -> logBus.error(
                        "⏱ 脚本运行超时（${settings.scriptTimeoutMinutes} 分钟），已中断",
                    )

                    exited -> logBus.success("■ 脚本已退出")
                    failure != null -> logBus.error("✘ 运行出错: ${failure.message}")
                    else -> logBus.success("✔ 脚本执行完成")
                }
                val status = when {
                    timedOut -> RunStatus.TIMEOUT
                    exited -> RunStatus.STOPPED
                    failure != null -> RunStatus.FAILED
                    else -> RunStatus.SUCCEEDED
                }
                val error = failure?.let { it.stackTraceToString().take(MAX_RUN_ERROR_CHARS) }
                RunLogRegistry.finish(session.runId, status, error)
                val success = !timedOut && (failure == null || exited)
                runCatching { onFinished?.invoke(success) }.onFailure {
                    logBus.warn("运行结束回调失败：${it.message}")
                }
            } finally {
                // 无论日志 / 停止流程是否抛异常，都必须释放资源与运行标记。
                session.releaseWakeLock()
                resetRunningState()
                runCatching { ScriptForegroundService.stop(appContext) }
                ScriptNotificationCenter.clear()
            }
        }
    }

    /** 预启动失败时回滚：结束 run 记录、释放锁、重置状态并停掉已拉起的前台服务。 */
    private fun rollbackStart(
        session: RunSession,
        error: Throwable,
        onFinished: ((Boolean) -> Unit)?,
    ) {
        RunLogRegistry.finish(
            session.runId,
            RunStatus.FAILED,
            error.stackTraceToString().take(MAX_RUN_ERROR_CHARS),
        )
        session.releaseWakeLock()
        resetRunningState()
        runCatching { ScriptForegroundService.stop(appContext) }
        ScriptNotificationCenter.clear()
        runCatching { onFinished?.invoke(false) }.onFailure {
            logBus.warn("运行结束回调失败：${it.message}")
        }
    }

    private fun resetRunningState() {
        _running.value = false
        _runningScript.value = null
        _runningScriptId.value = null
        _runningRunId.value = null
        activeSession = null
    }

    /** 请求停止当前脚本；通过 QuickJS 中断回调，纯计算死循环也能被打断。 */
    override fun requestStop() {
        val current = engine
        if (current != null) {
            logBus.info("… 正在停止脚本")
            current.requestExit()
            return
        }
        val session = activeSession
        if (session != null && _running.value) {
            // 引擎尚未创建（仍在校验 / 解锁 / 启动虚拟屏），先记下请求，创建后立即中断。
            session.stopRequested = true
            logBus.info("… 正在停止脚本（等待启动完成）")
            return
        }
        logBus.warn("当前没有正在运行的脚本")
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

    /** 一次运行的资源与状态句柄；所有释放操作都幂等，供异常路径复用。 */
    private inner class RunSession(val runId: String) {

        @Volatile
        var stopRequested: Boolean = false

        @Volatile
        var engine: JsEngine? = null

        @Volatile
        var startGenerationBefore: Long = 0L

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

        @Synchronized
        fun closeEngine() {
            val current = engine ?: return
            engine = null
            runCatching { current.close() }
        }
    }

    private companion object {
        /** KEYCODE_WAKEUP：点亮屏幕。 */
        const val KEYCODE_WAKEUP = 224

        /** 单次运行保留的错误堆栈上限，避免内存与返回体过大。 */
        const val MAX_RUN_ERROR_CHARS = 4_000
    }
}
