package com.benton.izukijs.runtime.api

import android.content.Context
import com.benton.izukijs.ai.AiApi
import com.benton.izukijs.ai.AiConfigRepository
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.controller.record.RecordingSession
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.data.ScriptEnvSession
import com.benton.izukijs.model.RunMode
import com.benton.izukijs.notification.NotificationDispatcher
import com.benton.izukijs.runtime.HttpSettingsRepository
import com.benton.izukijs.runtime.RuntimeSettingsRepository
import com.benton.izukijs.service.CaptureSettingsRepository
import com.benton.izukijs.service.ScreenCapture
import com.benton.izukijs.virtualscreen.VirtualScreenManager
import com.quickjs.JSContext
import java.io.Closeable

/**
 * 一次脚本运行的 API 装配。
 *
 * 命名空间的门控来自 [ScriptApiCatalog]：`@need` 只决定是否注册，不改变 API 行为。
 * 可变状态的 API（`SelectorApi` / `PipelineApi` / `RecordApi` / `ImageApi`）按运行创建，
 * 避免前台与后台脚本共享识别缓存或录制状态。
 */
class ScriptApiEnvironment(
    private val context: Context,
    private val controllers: ControllerManager,
    private val screenCapture: ScreenCapture,
    private val bundle: DeviceApiBundle,
    private val nodeTreeProvider: (timeoutMs: Long) -> NodeTreeResult,
    private val logBus: LogBus,
    private val aiConfigRepository: AiConfigRepository,
    private val captureSettingsRepository: CaptureSettingsRepository,
    private val scriptEnv: ScriptEnvSession,
    private val runtimeSettings: RuntimeSettingsRepository,
    private val httpSettingsRepository: HttpSettingsRepository,
    private val moduleSourceProvider: (String) -> String?,
    private val virtualScreen: VirtualScreenManager?,
    private val runMode: RunMode,
    private val scriptId: String,
    private val scriptName: String,
    private val runId: String? = null,
    private val pushDispatcher: NotificationDispatcher?,
    private val recordingSession: RecordingSession?,
    private val exitRequested: () -> Boolean,
    private val onExit: () -> Unit,
) : Closeable {

    private val screenshotStore = ScreenshotStore(
        context = context,
        settingsProvider = { captureSettingsRepository.current() },
        logBus = logBus,
    )

    private val runBundle: DeviceApiBundle by lazy {
        bundle.withExitRequested { exitRequested() }
    }

    private val imageApiDelegate = lazy {
        ImageApi(context, runBundle.screenshotProvider, logBus, runBundle.ocrProcessor)
    }
    private val imageApi by imageApiDelegate

    private val globalApi: GlobalApi by lazy {
        GlobalApi(
            context = context,
            logBus = logBus,
            captureProvider = runBundle.captureProvider,
            screenshotStore = screenshotStore,
            onExit = onExit,
            isExitRequested = exitRequested,
        )
    }

    private var recordApi: RecordApi? = null

    fun bind(ctx: JSContext, needs: Set<String>?) {
        val inputApi = runBundle.inputApi
        val deviceApi = runBundle.deviceApi
        val appApi = runBundle.appApi
        val shellApi = runBundle.shellApi
        val ocrApi = runBundle.ocrApi
        val selectorApi = runBundle.selectorApi

        ctx.appendJavascriptInterface(globalApi)
        ctx.appendJavascriptInterface(inputApi)
        ctx.addJavascriptInterface(deviceApi, "device")
        ctx.addJavascriptInterface(ConsoleApi(logBus, runId), "console")
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

        if (allowed(needs, "virtualScreen") && virtualScreen != null) {
            ctx.addJavascriptInterface(
                VirtualScreenApi(
                    manager = virtualScreen,
                    runModeProvider = { runMode },
                    captureProvider = runBundle.captureProvider,
                    screenshotStore = screenshotStore,
                    logBus = logBus,
                ),
                "virtualScreen",
            )
        }
        if (allowed(needs, "app")) ctx.addJavascriptInterface(appApi, "app")
        if (allowed(needs, "shell")) ctx.addJavascriptInterface(shellApi, "shell")
        if (allowed(needs, "images")) {
            ctx.addJavascriptInterface(imageApi, "images")
        }
        if (allowed(needs, "ocr")) ctx.addJavascriptInterface(ocrApi, "ocr")
        if (allowed(needs, "pipeline")) {
            ctx.addJavascriptInterface(
                PipelineApi(
                    screenshotProvider = runBundle.screenshotProvider,
                    screenSizeProvider = { deviceApi.width() to deviceApi.height() },
                    inputApi = inputApi,
                    appApi = appApi,
                    shellApi = shellApi,
                    visionEngine = imageApi.visionEngine(),
                    logBus = logBus,
                    isExitRequested = exitRequested,
                    recordingStore = recordingSession?.store,
                ),
                "pipeline",
            )
        }
        if (allowed(needs, "record") && recordingSession != null) {
            val api = RecordApi(recordingSession, logBus)
            recordApi = api
            ctx.addJavascriptInterface(api, "record")
        }
        if (allowed(needs, "notify")) ctx.addJavascriptInterface(runBundle.notificationApi, "notify")
        if (allowed(needs, "push") && pushDispatcher != null) {
            ctx.addJavascriptInterface(
                PushApi(
                    dispatcher = pushDispatcher,
                    logBus = logBus,
                    scriptId = scriptId,
                    scriptName = scriptName,
                ),
                "push",
            )
        }
        if (allowed(needs, "fs")) ctx.addJavascriptInterface(FileApi(logBus), "fs")
        if (allowed(needs, "selector")) ctx.addJavascriptInterface(selectorApi, "selector")
        if (allowed(needs, "clipboard")) {
            ctx.addJavascriptInterface(ClipboardApi(context, logBus), "clipboard")
        }
        if (allowed(needs, "http")) {
            ctx.addJavascriptInterface(
                HttpApi(logBus) { httpSettingsRepository.current() },
                "http",
            )
        }
        if (allowed(needs, "ai")) {
            ctx.addJavascriptInterface(
                AiApi(
                    configRepository = aiConfigRepository,
                    inputApi = inputApi,
                    selectorApi = selectorApi,
                    ocrApi = ocrApi,
                    appApi = appApi,
                    shellApi = shellApi,
                    deviceApi = deviceApi,
                    notificationApi = runBundle.notificationApi,
                    screenshotPathProvider = { globalApi.captureScreen("") },
                    nodeTreeProvider = nodeTreeProvider,
                    logBus = logBus,
                    isExitRequested = exitRequested,
                ),
                "ai",
            )
        }
    }

    override fun close() {
        runCatching { recordApi?.close() }
        recordApi = null
        if (imageApiDelegate.isInitialized()) runCatching { imageApi.close() }
    }

    private fun allowed(needs: Set<String>?, namespace: String): Boolean {
        if (needs == null) return true
        val need = ScriptApiCatalog.findNamespace(namespace)?.need ?: return true
        return need in needs
    }
}
