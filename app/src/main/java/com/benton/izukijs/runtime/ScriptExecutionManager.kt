package com.benton.izukijs.runtime

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationManagerCompat
import com.benton.izukijs.ai.AiConfigRepository
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.log.RunLogRegistry
import com.benton.izukijs.core.log.RunStatus
import com.benton.izukijs.data.GlobalEnvRepository
import com.benton.izukijs.data.RunHistoryRepository
import com.benton.izukijs.data.ScriptEnvRepository
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.EnvText
import com.benton.izukijs.model.RunMode
import com.benton.izukijs.model.ScriptEnvSpec
import com.benton.izukijs.ocr.OcrProcessor
import com.benton.izukijs.runtime.notification.ScriptNotificationCenter
import com.benton.izukijs.service.CaptureSettingsRepository
import com.benton.izukijs.service.FloatingWindowService
import com.benton.izukijs.service.ScreenCapture
import com.benton.izukijs.service.ScreenUnlockController
import com.benton.izukijs.service.ScriptForegroundService
import com.benton.izukijs.service.UnlockResult
import com.benton.izukijs.service.WakeLockController
import com.benton.izukijs.virtualscreen.VirtualScreenManager
import com.benton.izukijs.virtualscreen.VirtualScreenOptions
import com.benton.izukijs.virtualscreen.VirtualScreenSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 脚本执行管理器。执行不依赖 UI 生命周期：即使界面退到后台，前台服务也会保活。
 */
class ScriptExecutionManager(
    private val appContext: Context,
    private val controllers: ControllerManager,
    private val screenCapture: ScreenCapture,
    private val ocrProcessor: OcrProcessor,
    private val logBus: LogBus,
    private val aiConfigRepository: AiConfigRepository,
    private val captureSettingsRepository: CaptureSettingsRepository,
    private val scriptEnvRepository: ScriptEnvRepository,
    private val globalEnvRepository: GlobalEnvRepository,
    private val runHistoryRepository: RunHistoryRepository,
    private val runtimeSettings: RuntimeSettingsRepository,
    private val httpSettingsRepository: HttpSettingsRepository,
    private val wakeLock: WakeLockController,
    private val screenUnlock: ScreenUnlockController,
    private val virtualScreen: VirtualScreenManager,
    private val virtualScreenSettings: VirtualScreenSettingsRepository,
    private val moduleSourceProvider: (String) -> String?,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _runningScript = MutableStateFlow<String?>(null)
    val runningScript: StateFlow<String?> = _runningScript.asStateFlow()

    private val _runningScriptId = MutableStateFlow<String?>(null)
    val runningScriptId: StateFlow<String?> = _runningScriptId.asStateFlow()

    private val _runningRunId = MutableStateFlow<String?>(null)
    val runningRunId: StateFlow<String?> = _runningRunId.asStateFlow()

    @Volatile
    private var lastStartedRunId: String? = null

    fun lastRunId(): String? = lastStartedRunId

    private var job: Job? = null

    @Volatile
    private var engine: JsEngine? = null

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
        // 解析脚本头部声明的运行参数，按「默认值 < 全局变量 < 脚本变量 < 本次传入值」合并。
        val spec = ScriptEnvSpec.parse(source)
        val useGlobal = scriptEnvRepository.useGlobalEnv(scriptId)
        val resolved = EnvText.resolve(
            defaults = spec.defaults(),
            global = globalEnvRepository.current(),
            script = scriptEnvRepository.env(scriptId),
            runtime = scriptEnvRepository.values(scriptId) + values,
            useGlobal = useGlobal,
        )
        if (values.isNotEmpty()) {
            // 只持久化本次表单输入与已有脚本参数，避免把全局变量快照写进脚本参数。
            scriptEnvRepository.saveValues(
                scriptId,
                spec.defaults() + scriptEnvRepository.values(scriptId) + values,
            )
        }
        val scriptEnv = ScriptEnv(scriptId, resolved, scriptEnvRepository)
        if (resolved.isNotEmpty()) {
            // 只记录键名，避免把可能含密钥的变量值写入日志。
            logBus.info("⚙ 运行参数: " + resolved.keys.sorted().joinToString(", "))
        }
        val requestedRunMode = RunMode.from(resolved[RUN_MODE_KEY])
        val virtualSettings = virtualScreenSettings.current()
        var effectiveRunMode = requestedRunMode ?: virtualSettings.defaultMode
        if (effectiveRunMode == RunMode.BACKGROUND &&
            (!virtualSettings.backgroundAllowed || !virtualScreen.available())
        ) {
            logBus.warn("后台虚拟屏不可用或未允许，本次回退前台模式")
            effectiveRunMode = RunMode.FOREGROUND
        }

        _runningScript.value = scriptName
        _runningScriptId.value = scriptId
        _runningRunId.value = runId
        runHistoryRepository.record(scriptId, scriptName)
        logBus.info("▶ 开始运行: $scriptName")
        if (needs != null) {
            logBus.info("🔒 声明能力: " + needs.sorted().joinToString(", "))
            warnMissingNeeds(needs)
        }
        ScriptNotificationCenter.begin(scriptName)
        ScriptForegroundService.start(appContext, scriptName)
        FloatingWindowService.showForRun(appContext)

        job = scope.launch {
            RunLogRegistry.enter(runId)
            try {
            var jsEngine: JsEngine? = null
            var failure: Throwable? = null
            val settings = runtimeSettings.current()
            if (screenUnlock.isEnabled()) {
                val result = screenUnlock.ensureUnlocked()
                if (result != UnlockResult.OK &&
                    result != UnlockResult.ALREADY_UNLOCKED &&
                    result != UnlockResult.DISABLED
                ) {
                    logBus.warn("自动解锁未完成: $result")
                }
            }
            if (settings.keepAwake) wakeLock.acquire()
            if (settings.wakeScreen) {
                runCatching { controllers.controllerFor(Capability.KEY)?.pressKey(KEYCODE_WAKEUP) }
            }
            val startGenerationBefore = virtualScreen.startGeneration()
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
            try {
                jsEngine = JsEngine(
                    appContext,
                    controllers,
                    screenCapture,
                    ocrProcessor,
                    logBus,
                    aiConfigRepository,
                    captureSettingsRepository,
                    scriptEnv,
                    runtimeSettings,
                    httpSettingsRepository,
                    moduleSourceProvider,
                    virtualScreen,
                    effectiveRunMode,
                ).also { engine = it }
                jsEngine.execute(
                    source = source,
                    fileName = scriptName,
                    timeoutMs = settings.scriptTimeoutMinutes * 60_000L,
                    needs = needs,
                )
            } catch (t: Throwable) {
                failure = t
            } finally {
                if (settings.keepAwake) wakeLock.release()
                val timedOut = jsEngine?.timedOut == true
                val exited = jsEngine?.exitRequested == true || isExitThrowable(failure)
                jsEngine?.let { runCatching { it.close() } }
                engine = null
                val startedThisRun = virtualScreen.startGeneration() > startGenerationBefore
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
                RunLogRegistry.finish(runId, status, error)
                _running.value = false
                _runningScript.value = null
                _runningScriptId.value = null
                _runningRunId.value = null
                ScriptForegroundService.stop(appContext)
                ScriptNotificationCenter.clear()
                val success = !timedOut && (failure == null || exited)
                runCatching { onFinished?.invoke(success) }
            }
            } finally {
                RunLogRegistry.exit()
            }
        }
        return true
    }

    private fun warnMissingNeeds(needs: Set<String>) {
        if ("http" in needs && !httpSettingsRepository.current().enabled) {
            logBus.warn("@need http 已声明，但「脚本网络」未启用")
        }
        if ("shell" in needs && controllers.controllerFor(Capability.SHELL)?.isReady() != true) {
            logBus.warn("@need shell 已声明，但 Shizuku / 无线调试 / Root 未就绪")
        }
        if ("selector" in needs &&
            controllers.controllerFor(Capability.NODE_TREE)?.isReady() != true
        ) {
            logBus.warn("@need selector 已声明，但无障碍未就绪")
        }
        if ("notify" in needs &&
            !NotificationManagerCompat.getEnabledListenerPackages(appContext)
                .contains(appContext.packageName)
        ) {
            logBus.warn("@need notify 已声明，但通知监听未授权")
        }
        if ("ai" in needs && !aiConfigRepository.current().isConfigured) {
            logBus.warn("@need ai 已声明，但 AI Agent 尚未配置")
        }
        if ("fs" in needs &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            logBus.warn("@need fs 已声明，但未授予「全部文件访问」；仅应用私有目录可用")
        }
        if ("virtualscreen" in needs && !virtualScreen.available()) {
            logBus.warn("@need virtualscreen 已声明，但 Shizuku 未授权或系统版本低于 Android 10")
        }
        if ("selector" in needs && virtualScreen.isActive()) {
            logBus.warn("@need selector 在后台虚拟屏上不可用，请改用 capture / ocr / 坐标输入")
        }
    }

    /** 请求停止当前脚本；通过 QuickJS 中断回调，纯计算死循环也能被打断。 */
    fun requestStop() {
        val current = engine
        if (current == null) {
            logBus.warn("当前没有正在运行的脚本")
            return
        }
        logBus.info("… 正在停止脚本")
        current.requestExit()
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

    private companion object {
        /** KEYCODE_WAKEUP：点亮屏幕。 */
        const val KEYCODE_WAKEUP = 224

        /** 脚本头部 `@env RUN_MODE=background` 使用的键名。 */
        const val RUN_MODE_KEY = "RUN_MODE"

        /** 单次运行保留的错误堆栈上限，避免内存与返回体过大。 */
        const val MAX_RUN_ERROR_CHARS = 4_000
    }
}
