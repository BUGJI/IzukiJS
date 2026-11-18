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
    /** 当前 run 是否仍持有运行位；用于虚拟屏归属与通知写入。 */
    private val isRunActive: () -> Boolean = { true },
) : Closeable {

    private val bridge = com.benton.izukijs.runtime.QuickJsBridge(logBus)

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

        // bundle 由脚本与 MCP 共享；每次 run 绑定新环境时清空跨 run 的输入 / 前台缓存。
        inputApi.resetRunState()
        appApi.resetRunState()

        bridge.appendGlobal(ctx, globalApi)
        bridge.appendGlobal(ctx, inputApi)
        registerNamespace(ctx, deviceApi, "device")
        registerNamespace(ctx, ConsoleApi(logBus, runId), "console")
        registerNamespace(
            ctx,
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
        registerNamespace(ctx, EnvApi(scriptEnv), "env")
        registerNamespace(ctx, StateApi(scriptEnv), "state")
        registerNamespace(ctx, ModuleApi(moduleSourceProvider), "modules")

        if (allowed(needs, "virtualScreen") && virtualScreen != null) {
            registerNamespace(
                ctx,
                VirtualScreenApi(
                    manager = virtualScreen,
                    runModeProvider = { runMode },
                    captureProvider = runBundle.captureProvider,
                    screenshotStore = screenshotStore,
                    logBus = logBus,
                    ownerRunId = runId,
                    isRequesterCurrent = isRunActive,
                ),
                "virtualScreen",
            )
        }
        if (allowed(needs, "app")) registerNamespace(ctx, appApi, "app")
        if (allowed(needs, "shell")) registerNamespace(ctx, shellApi, "shell")
        if (allowed(needs, "images")) {
            registerNamespace(ctx, imageApi, "images")
        }
        if (allowed(needs, "ocr")) registerNamespace(ctx, ocrApi, "ocr")
        if (allowed(needs, "pipeline")) {
            registerNamespace(
                ctx,
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
            registerNamespace(ctx, api, "record")
        }
        if (allowed(needs, "notify")) {
            registerNamespace(
                ctx,
                runBundle.notificationApi.forRun(runId, exitRequested),
                "notify",
            )
        }
        if (allowed(needs, "events")) {
            registerNamespace(ctx, EventsApi(exitRequested), "events")
        }
        if (allowed(needs, "floaty")) {
            registerNamespace(ctx, FloatyApi(runId, exitRequested), "floaty")
        }
        if (allowed(needs, "push") && pushDispatcher != null) {
            registerNamespace(
                ctx,
                PushApi(
                    dispatcher = pushDispatcher,
                    logBus = logBus,
                    scriptId = scriptId,
                    scriptName = scriptName,
                ),
                "push",
            )
        }
        if (allowed(needs, "fs")) registerNamespace(ctx, FileApi(logBus), "fs")
        if (allowed(needs, "selector")) registerNamespace(ctx, selectorApi, "selector")
        if (allowed(needs, "clipboard")) {
            registerNamespace(ctx, ClipboardApi(context, logBus), "clipboard")
        }
        if (allowed(needs, "http")) {
            registerNamespace(
                ctx,
                HttpApi(logBus) { httpSettingsRepository.current() },
                "http",
            )
        }
        if (allowed(needs, "ai")) {
            registerNamespace(
                ctx,
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
        runCatching { com.benton.izukijs.service.ScriptOverlayManager.closeAll(runId) }
        runCatching { recordApi?.close() }
        recordApi = null
        if (imageApiDelegate.isInitialized()) runCatching { imageApi.close() }
    }

    /**
     * 注册脚本命名空间。
     *
     * 目录是单一事实源：未登记到 [ScriptApiCatalog] 的命名空间一律视为漏改，
     * 直接失败而不是悄悄暴露给脚本。
     */
    private fun registerNamespace(ctx: JSContext, target: Any, name: String) {
        require(ScriptApiCatalog.findNamespace(name) != null) {
            "脚本命名空间未登记到 ScriptApiCatalog: $name"
        }
        bridge.addNamespace(ctx, target, name)
    }

    private fun allowed(needs: Set<String>?, namespace: String): Boolean {
        if (needs == null) return true
        // 注册前已由 registerNamespace 校验存在；这里再失败关闭，避免目录被误删后
        // 把命名空间当作 always-on 继续暴露。
        val need = ScriptApiCatalog.findNamespace(namespace)?.need ?: return false
        return need in needs
    }
}
