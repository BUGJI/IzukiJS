package com.benton.izukijs.runtime

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationManagerCompat
import com.benton.izukijs.ai.AiConfigRepository
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.data.GlobalEnvRepository
import com.benton.izukijs.data.RunHistoryRepository
import com.benton.izukijs.data.ScriptEnvRepository
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.EnvText
import com.benton.izukijs.model.ScriptEnvSpec
import com.benton.izukijs.ocr.OcrProcessor
import com.benton.izukijs.service.CaptureSettingsRepository
import com.benton.izukijs.service.FloatingWindowService
import com.benton.izukijs.service.ScreenCapture
import com.benton.izukijs.service.ScriptForegroundService
import com.benton.izukijs.service.WakeLockController
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
    private val moduleSourceProvider: (String) -> String?,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _runningScript = MutableStateFlow<String?>(null)
    val runningScript: StateFlow<String?> = _runningScript.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var engine: JsEngine? = null

    fun run(
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
        // 解析脚本头部声明的运行参数，按「默认值 < 全局变量 < 脚本变量 < 本次传入值」合并。
        val spec = ScriptEnvSpec.parse(source)
        val useGlobal = scriptEnvRepository.useGlobalEnv(scriptName)
        val resolved = EnvText.resolve(
            defaults = spec.defaults(),
            global = globalEnvRepository.current(),
            script = scriptEnvRepository.env(scriptName),
            runtime = scriptEnvRepository.values(scriptName) + values,
            useGlobal = useGlobal,
        )
        if (values.isNotEmpty()) {
            // 只持久化本次表单输入与已有脚本参数，避免把全局变量快照写进脚本参数。
            scriptEnvRepository.saveValues(
                scriptName,
                spec.defaults() + scriptEnvRepository.values(scriptName) + values,
            )
        }
        val scriptEnv = ScriptEnv(scriptName, resolved, scriptEnvRepository)
        if (resolved.isNotEmpty()) {
            // 只记录键名，避免把可能含密钥的变量值写入日志。
            logBus.info("⚙ 运行参数: " + resolved.keys.sorted().joinToString(", "))
        }

        _runningScript.value = scriptName
        runHistoryRepository.record(scriptName)
        logBus.info("▶ 开始运行: $scriptName")
        if (needs != null) {
            logBus.info("🔒 声明能力: " + needs.sorted().joinToString(", "))
            warnMissingNeeds(needs)
        }
        ScriptForegroundService.start(appContext, scriptName)
        FloatingWindowService.showForRun(appContext)

        job = scope.launch {
            var jsEngine: JsEngine? = null
            var failure: Throwable? = null
            val settings = runtimeSettings.current()
            if (settings.keepAwake) wakeLock.acquire()
            if (settings.wakeScreen) {
                runCatching { controllers.controllerFor(Capability.KEY)?.pressKey(KEYCODE_WAKEUP) }
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
                when {
                    timedOut -> logBus.error(
                        "⏱ 脚本运行超时（${settings.scriptTimeoutMinutes} 分钟），已中断",
                    )

                    exited -> logBus.success("■ 脚本已退出")
                    failure != null -> logBus.error("✘ 运行出错: ${failure.message}")
                    else -> logBus.success("✔ 脚本执行完成")
                }
                _running.value = false
                _runningScript.value = null
                ScriptForegroundService.stop(appContext)
                val success = !timedOut && (failure == null || exited)
                runCatching { onFinished?.invoke(success) }
            }
        }
        return true
    }

    private fun warnMissingNeeds(needs: Set<String>) {
        if ("http" in needs && !httpSettingsRepository.current().enabled) {
            logBus.warn("@need http 已声明，但「脚本网络」未启用")
        }
        if ("shell" in needs && controllers.controllerFor(Capability.SHELL)?.isReady() != true) {
            logBus.warn("@need shell 已声明，但 Shizuku / Root 未就绪")
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
    }
}
