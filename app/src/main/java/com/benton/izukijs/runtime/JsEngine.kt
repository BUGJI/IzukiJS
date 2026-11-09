package com.benton.izukijs.runtime

import android.content.Context
import com.benton.izukijs.ai.AiApi
import com.benton.izukijs.ai.AiConfigRepository
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.data.ScriptEnvSession
import com.benton.izukijs.runtime.api.ClipboardApi
import com.benton.izukijs.runtime.api.ConsoleApi
import com.benton.izukijs.runtime.api.DeviceApiBundle
import com.benton.izukijs.runtime.api.EnvApi
import com.benton.izukijs.runtime.api.FileApi
import com.benton.izukijs.runtime.api.GlobalApi
import com.benton.izukijs.runtime.api.HttpApi
import com.benton.izukijs.runtime.api.ImageApi
import com.benton.izukijs.runtime.api.ModuleApi
import com.benton.izukijs.runtime.api.PermissionsApi
import com.benton.izukijs.runtime.api.ScreenshotStore
import com.benton.izukijs.runtime.api.StateApi
import com.benton.izukijs.runtime.api.VirtualScreenApi
import com.benton.izukijs.model.RunMode
import com.benton.izukijs.virtualscreen.VirtualScreenManager
import com.benton.izukijs.service.CaptureSettingsRepository
import com.benton.izukijs.service.ScreenCapture
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
) : Closeable {

    private var runtime: QuickJS? = null
    private var jsContext: JSContext? = null

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
        bindApis(ctx, needs)
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

    private fun bindApis(ctx: JSContext, needs: Set<String>?) {
        // 复用容器级 bundle，只替换 SelectorApi 的退出回调，避免每次运行重复组装。
        val bundle = deviceApiBundle.withExitRequested { exitRequested }
        val screenshotStore = ScreenshotStore(
            context = context,
            settingsProvider = { captureSettingsRepository.current() },
            logBus = logBus,
        )
        val globalApi = GlobalApi(
            context = context,
            logBus = logBus,
            captureProvider = bundle.captureProvider,
            screenshotStore = screenshotStore,
            onExit = { requestExit() },
            isExitRequested = { exitRequested },
        )
        val inputApi = bundle.inputApi
        val deviceApi = bundle.deviceApi
        val appApi = bundle.appApi
        val shellApi = bundle.shellApi
        val ocrApi = bundle.ocrApi
        val selectorApi = bundle.selectorApi
        val allowed: (String) -> Boolean = { name -> needs == null || name in needs }

        ctx.appendJavascriptInterface(globalApi)
        ctx.appendJavascriptInterface(inputApi)
        ctx.addJavascriptInterface(deviceApi, "device")
        ctx.addJavascriptInterface(ConsoleApi(logBus), "console")
        ctx.addJavascriptInterface(
            PermissionsApi(
                context,
                controllers,
                screenCapture,
                { runtimeSettings.current().appList },
                { httpSettingsRepository.current().enabled },
                virtualScreen,
                { runMode },
            ),
            "permissions",
        )
        ctx.addJavascriptInterface(EnvApi(scriptEnv), "env")
        ctx.addJavascriptInterface(StateApi(scriptEnv), "state")
        ctx.addJavascriptInterface(ModuleApi(moduleSourceProvider), "modules")

        if (allowed("virtualscreen") && virtualScreen != null) {
            ctx.addJavascriptInterface(
                VirtualScreenApi(
                    manager = virtualScreen,
                    runModeProvider = { runMode },
                    captureProvider = bundle.captureProvider,
                    screenshotStore = screenshotStore,
                    logBus = logBus,
                ),
                "virtualScreen",
            )
        }
        if (allowed("app")) ctx.addJavascriptInterface(appApi, "app")
        if (allowed("shell")) ctx.addJavascriptInterface(shellApi, "shell")
        if (allowed("images")) {
            ctx.addJavascriptInterface(ImageApi(context, bundle.screenshotProvider, logBus), "images")
        }
        if (allowed("ocr")) ctx.addJavascriptInterface(ocrApi, "ocr")
        if (allowed("notify")) ctx.addJavascriptInterface(bundle.notificationApi, "notify")
        if (allowed("fs")) ctx.addJavascriptInterface(FileApi(logBus), "fs")
        if (allowed("selector")) ctx.addJavascriptInterface(selectorApi, "selector")
        if (allowed("clipboard")) {
            ctx.addJavascriptInterface(ClipboardApi(context, logBus), "clipboard")
        }
        if (allowed("http")) {
            ctx.addJavascriptInterface(
                HttpApi(logBus) { httpSettingsRepository.current() },
                "http",
            )
        }
        if (allowed("ai")) {
            ctx.addJavascriptInterface(
                AiApi(
                    configRepository = aiConfigRepository,
                    inputApi = inputApi,
                    selectorApi = selectorApi,
                    ocrApi = ocrApi,
                    appApi = appApi,
                    shellApi = shellApi,
                    deviceApi = deviceApi,
                    notificationApi = bundle.notificationApi,
                    screenshotPathProvider = { globalApi.captureScreen("") },
                    nodeTreeProvider = nodeTreeProvider,
                    logBus = logBus,
                    isExitRequested = { exitRequested },
                ),
                "ai",
            )
        }
    }

    override fun close() {
        stopWatchdog()
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
