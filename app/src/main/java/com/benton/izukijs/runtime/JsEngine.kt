package com.benton.izukijs.runtime

import android.content.Context
import com.benton.izukijs.ai.AiConfigRepository
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.data.ScriptEnvSession
import com.benton.izukijs.model.RunMode
import com.benton.izukijs.notification.NotificationDispatcher
import com.benton.izukijs.runtime.api.DeviceApiBundle
import com.benton.izukijs.runtime.api.ScriptApiEnvironment
import com.benton.izukijs.service.CaptureSettingsRepository
import com.benton.izukijs.service.ScreenCapture
import com.benton.izukijs.virtualscreen.VirtualScreenManager
import com.quickjs.JSContext
import com.quickjs.QuickJS
import java.io.Closeable

/**
 * QuickJS 运行时封装。每个脚本一个实例，拥有独立的运行时与上下文。
 */
class JsEngine(
    private val context: Context,
    private val controllers: ControllerManager,
    private val screenCapture: ScreenCapture,
    private val deviceApiBundle: DeviceApiBundle,
    private val nodeTreeProvider: (timeoutMs: Long) -> NodeTreeResult,
    private val logBus: LogBus,
    private val aiConfigRepository: AiConfigRepository,
    private val captureSettingsRepository: CaptureSettingsRepository,
    private val scriptEnv: ScriptEnvSession,
    private val runtimeSettings: RuntimeSettingsRepository,
    private val httpSettingsRepository: HttpSettingsRepository,
    private val moduleSourceProvider: (String) -> String?,
    private val virtualScreen: VirtualScreenManager? = null,
    private val runMode: RunMode = RunMode.FOREGROUND,
    private val scriptId: String = "",
    private val scriptName: String = "",
    private val pushDispatcher: NotificationDispatcher? = null,
    private val recordingSession: com.benton.izukijs.controller.record.RecordingSession? = null,
) : Closeable {

    private var runtime: QuickJS? = null
    private var jsContext: JSContext? = null
    private var apiEnvironment: ScriptApiEnvironment? = null

    private val preludeSource: String by lazy {
        context.assets.open(PRELUDE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Volatile
    private var interrupt: QuickJsInterrupt? = null

    @Volatile
    private var watchdog: Thread? = null

    @Volatile
    var exitRequested: Boolean = false
        private set

    /** 是否因运行超时被中断（与用户主动停止区分）。 */
    @Volatile
    var timedOut: Boolean = false
        private set

    fun requestExit() {
        exitRequested = true
        interrupt?.stop()
    }

    /**
     * @param timeoutMs 运行超时（毫秒），<= 0 表示不限制。超时由看门狗请求退出，
     *   并通过 [QuickJsInterrupt] 打断纯计算死循环。
     */
    fun execute(
        source: String,
        fileName: String,
        timeoutMs: Long = 0L,
        needs: Set<String>? = null,
    ) {
        val rt = QuickJS.createRuntimeWithEventQueue()
        runtime = rt
        val installed = QuickJsInterrupt.install(rt)
        interrupt = installed
        if (exitRequested) installed?.stop()
        val ctx = rt.createContext()
        jsContext = ctx
        val env = ScriptApiEnvironment(
            context = context,
            controllers = controllers,
            screenCapture = screenCapture,
            bundle = deviceApiBundle,
            nodeTreeProvider = nodeTreeProvider,
            logBus = logBus,
            aiConfigRepository = aiConfigRepository,
            captureSettingsRepository = captureSettingsRepository,
            scriptEnv = scriptEnv,
            runtimeSettings = runtimeSettings,
            httpSettingsRepository = httpSettingsRepository,
            moduleSourceProvider = moduleSourceProvider,
            virtualScreen = virtualScreen,
            runMode = runMode,
            scriptId = scriptId,
            scriptName = scriptName,
            pushDispatcher = pushDispatcher,
            recordingSession = recordingSession,
            exitRequested = { exitRequested },
            onExit = { requestExit() },
        )
        apiEnvironment = env
        env.bind(ctx, needs)
        startWatchdog(timeoutMs)
        try {
            ctx.executeVoidScript(preludeSource, "izuki_prelude.js")
            ctx.executeVoidScript(source, fileName)
        } finally {
            stopWatchdog()
        }
    }

    private fun startWatchdog(timeoutMs: Long) {
        if (timeoutMs <= 0L) return
        val thread = Thread({
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) {
                        if (!exitRequested) {
                            timedOut = true
                            requestExit()
                        }
                        return@Thread
                    }
                    Thread.sleep(minOf(WATCHDOG_INTERVAL_MS, remaining / 1_000_000L + 1L))
                }
            } catch (_: InterruptedException) {
                // 脚本已结束或停止，正常退出。
            }
        }, "izuki-script-timeout").apply { isDaemon = true }
        watchdog = thread
        thread.start()
    }

    private fun stopWatchdog() {
        watchdog?.interrupt()
        watchdog = null
    }

    override fun close() {
        stopWatchdog()
        runCatching { apiEnvironment?.close() }
        apiEnvironment = null
        runCatching { interrupt?.close() }
        interrupt = null
        runCatching { jsContext?.close() }
        runCatching { runtime?.close() }
        jsContext = null
        runtime = null
    }

    private companion object {
        const val WATCHDOG_INTERVAL_MS = 200L

        /** 放在 runtime/ 下，避免被 ScriptRepository.seedBundledScripts 当成用户脚本复制。 */
        const val PRELUDE_ASSET = "runtime/prelude.js"
    }
}
