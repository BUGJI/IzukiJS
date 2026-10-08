package com.benton.izukijs.runtime

import android.content.Context
import com.benton.izukijs.ai.AiConfigRepository
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.data.RunHistoryRepository
import com.benton.izukijs.data.ScriptEnvRepository
import com.benton.izukijs.model.ScriptEnvSpec
import com.benton.izukijs.ocr.OcrProcessor
import com.benton.izukijs.service.CaptureSettingsRepository
import com.benton.izukijs.service.FloatingWindowService
import com.benton.izukijs.service.ScreenCapture
import com.benton.izukijs.service.ScriptForegroundService
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
    private val runHistoryRepository: RunHistoryRepository,
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

    fun run(scriptName: String, source: String, values: Map<String, String> = emptyMap()) {
        if (!_running.compareAndSet(expect = false, update = true)) {
            logBus.warn("已有脚本正在运行，请先停止")
            return
        }
        // 解析脚本头部声明的运行参数，合并（默认值 < 上次保存值 < 本次传入值）。
        val spec = ScriptEnvSpec.parse(source)
        val resolved = spec.defaults() + scriptEnvRepository.values(scriptName) + values
        if (values.isNotEmpty()) scriptEnvRepository.saveValues(scriptName, resolved)
        val scriptEnv = ScriptEnv(scriptName, resolved, scriptEnvRepository)
        if (resolved.isNotEmpty()) {
            logBus.info("⚙ 运行参数: " + resolved.entries.joinToString(", ") { "${it.key}=${it.value}" })
        }

        _runningScript.value = scriptName
        runHistoryRepository.record(scriptName)
        logBus.info("▶ 开始运行: $scriptName")
        ScriptForegroundService.start(appContext, scriptName)
        FloatingWindowService.showForRun(appContext)

        job = scope.launch {
            var jsEngine: JsEngine? = null
            var failure: Throwable? = null
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
                    moduleSourceProvider,
                ).also { engine = it }
                jsEngine.execute(source, scriptName)
            } catch (t: Throwable) {
                failure = t
            } finally {
                val exited = jsEngine?.exitRequested == true || isExitThrowable(failure)
                jsEngine?.let { runCatching { it.close() } }
                engine = null
                when {
                    exited -> logBus.success("■ 脚本已退出")
                    failure != null -> logBus.error("✘ 运行出错: ${failure.message}")
                    else -> logBus.success("✔ 脚本执行完成")
                }
                _running.value = false
                _runningScript.value = null
                ScriptForegroundService.stop(appContext)
            }
        }
    }

    /** 请求停止当前脚本。脚本会在下一次 sleep/检查点退出。 */
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
}
