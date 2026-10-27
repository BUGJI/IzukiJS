package com.benton.izukijs.di

import android.app.Application
import android.content.Context
import com.benton.izukijs.BuildConfig
import com.benton.izukijs.IzukiApp
import com.benton.izukijs.ai.AiConfigRepository
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.ControllerSettingsRepository
import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.controller.UiautomatorDumper
import com.benton.izukijs.controller.hid.HidManager
import com.benton.izukijs.controller.root.RootManager
import com.benton.izukijs.controller.shizuku.ShizukuManager
import com.benton.izukijs.data.AppearanceRepository
import com.benton.izukijs.data.ConfigBackupManager
import com.benton.izukijs.data.EditorSettingsRepository
import com.benton.izukijs.data.GlobalEnvRepository
import com.benton.izukijs.data.RunHistoryRepository
import com.benton.izukijs.data.ScriptEnvRepository
import com.benton.izukijs.data.ScriptImporter
import com.benton.izukijs.data.ScriptListPreferences
import com.benton.izukijs.data.ScriptRepository
import com.benton.izukijs.data.TileSettingsRepository
import com.benton.izukijs.i18n.LanguageRepository
import com.benton.izukijs.mcp.McpAuditLog
import com.benton.izukijs.mcp.McpConfigRepository
import com.benton.izukijs.mcp.McpServerController
import com.benton.izukijs.mcp.resources.McpResources
import com.benton.izukijs.mcp.tools.McpToolRegistry
import com.benton.izukijs.ocr.OcrConfigRepository
import com.benton.izukijs.ocr.OcrProcessor
import com.benton.izukijs.runtime.HttpSettingsRepository
import com.benton.izukijs.runtime.LogBus
import com.benton.izukijs.runtime.LogFileStore
import com.benton.izukijs.runtime.LogSettingsRepository
import com.benton.izukijs.runtime.RuntimeSettingsRepository
import com.benton.izukijs.runtime.ScriptExecutionManager
import com.benton.izukijs.runtime.api.DeviceApiBundle
import com.benton.izukijs.schedule.ScheduleManager
import com.benton.izukijs.schedule.ScheduleRepository
import com.benton.izukijs.schedule.ScheduleRunner
import com.benton.izukijs.service.CaptureSettingsRepository
import com.benton.izukijs.service.ScreenCapture
import com.benton.izukijs.service.WakeLockController
import com.benton.izukijs.subscription.SubscriptionManager
import com.benton.izukijs.subscription.SubscriptionRepository
import com.benton.izukijs.update.UpdateManager
import com.benton.izukijs.update.UpdateSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 轻量手写依赖容器。规模变大后可平滑替换为 Hilt/Koin。
 */
class AppContainer(private val application: Application) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val controllerManager = ControllerManager()

    val controllerSettingsRepository = ControllerSettingsRepository(application)

    val captureSettingsRepository = CaptureSettingsRepository(application)

    val logBus = LogBus()

    val logSettingsRepository = LogSettingsRepository(application)

    val logFileStore = LogFileStore(application, logSettingsRepository)

    val scriptRepository = ScriptRepository(application)

    val scriptImporter = ScriptImporter(application, scriptRepository, logBus)

    val scriptListPreferences = ScriptListPreferences(application)

    val scriptEnvRepository = ScriptEnvRepository(application)

    val globalEnvRepository = GlobalEnvRepository(application)

    val runHistoryRepository = RunHistoryRepository(application)

    val tileSettingsRepository = TileSettingsRepository(application)

    val runtimeSettingsRepository = RuntimeSettingsRepository(application)

    val httpSettingsRepository = HttpSettingsRepository(application)

    val wakeLockController = WakeLockController(application)

    val screenCapture = ScreenCapture(logBus)

    val ocrConfigRepository = OcrConfigRepository(application)

    val ocrProcessor = OcrProcessor(ocrConfigRepository, captureSettingsRepository, logBus)

    val aiConfigRepository = AiConfigRepository(application)

    val editorSettingsRepository = EditorSettingsRepository(application)

    val languageRepository = LanguageRepository(application)

    val appearanceRepository = AppearanceRepository(application)

    val updateSettingsRepository = UpdateSettingsRepository(application)

    val updateManager = UpdateManager(application, updateSettingsRepository, logBus)

    val configBackupManager = ConfigBackupManager(
        aiConfigRepository = aiConfigRepository,
        ocrConfigRepository = ocrConfigRepository,
        controllerSettingsRepository = controllerSettingsRepository,
        captureSettingsRepository = captureSettingsRepository,
        logSettingsRepository = logSettingsRepository,
        httpSettingsRepository = httpSettingsRepository,
        editorSettingsRepository = editorSettingsRepository,
        appearanceRepository = appearanceRepository,
        updateSettingsRepository = updateSettingsRepository,
        globalEnvRepository = globalEnvRepository,
    )

    val scriptExecutionManager = ScriptExecutionManager(
        application,
        controllerManager,
        screenCapture,
        ocrProcessor,
        logBus,
        aiConfigRepository,
        captureSettingsRepository,
        scriptEnvRepository,
        globalEnvRepository,
        runHistoryRepository,
        runtimeSettingsRepository,
        httpSettingsRepository,
        wakeLockController,
        moduleSourceProvider = { raw ->
            val id = raw.trim().removePrefix("./").removeSuffix(".js")
            scriptRepository.find(id)?.let { scriptRepository.read(it) }
        },
    )

    val mcpConfigRepository = McpConfigRepository(application)

    val mcpAuditLog = McpAuditLog(application)

    /** 脚本与 MCP 共用的设备 API 集合；懒加载，首次使用时组装。 */
    val deviceApiBundle: DeviceApiBundle by lazy {
        DeviceApiBundle.create(
            application,
            controllerManager,
            screenCapture,
            ocrProcessor,
            logBus,
            appListEnabled = { runtimeSettingsRepository.current().appList },
        )
    }

    /** 读控件树：无障碍优先，Shizuku / Root 的 uiautomator dump 兜底。 */
    private val nodeTreeProvider: () -> NodeTreeResult = {
        UiautomatorDumper.read(controllerManager, logBus)
    }

    val mcpServerController = McpServerController(
        logBus = logBus,
        auditLog = mcpAuditLog,
        configProvider = { mcpConfigRepository.current() },
        registryFactory = { operations ->
            McpToolRegistry(
                bundle = deviceApiBundle,
                nodeTreeProvider = nodeTreeProvider,
                scriptRepository = scriptRepository,
                executionManager = scriptExecutionManager,
                operations = operations,
                logFileStore = logFileStore,
                configProvider = { mcpConfigRepository.current() },
            )
        },
        resourcesFactory = {
            McpResources(
                bundle = deviceApiBundle,
                controllers = controllerManager,
                scriptRepository = scriptRepository,
                executionManager = scriptExecutionManager,
                logFileStore = logFileStore,
                auditLog = mcpAuditLog,
            )
        },
        serverVersion = BuildConfig.VERSION_NAME,
    )

    val shizukuManager = ShizukuManager(application, controllerManager, logBus)

    val rootManager = RootManager(application, controllerManager, logBus, appScope)

    val hidManager = HidManager(application, controllerManager, logBus, appScope)

    val scheduleRepository = ScheduleRepository(application)

    val scheduleManager = ScheduleManager(application, scheduleRepository, logBus)

    val subscriptionRepository = SubscriptionRepository(application)

    val subscriptionManager = SubscriptionManager(
        application,
        subscriptionRepository,
        scriptRepository,
        logBus,
    )

    val scheduleRunner = ScheduleRunner(
        context = application,
        repository = scheduleRepository,
        scriptRepository = scriptRepository,
        scheduleManager = scheduleManager,
        executionManager = scriptExecutionManager,
        foregroundProvider = { deviceApiBundle.appApi.currentPackage() },
        logBus = logBus,
    )

    /** 初始化控制后端。会注册 Shizuku 监听并异步探测 Root。 */
    fun init() {
        logBus.setSink { logFileStore.append(it) }
        appScope.launch { scriptRepository.seedBundledScripts() }
        shizukuManager.init()
        rootManager.init()
        hidManager.init()
        scheduleManager.start()
        // 控制偏好以持久化的 Repository 为唯一数据源，实时同步到内存中的 ControllerManager。
        appScope.launch {
            controllerSettingsRepository.settings.collect { settings ->
                controllerManager.readMode = settings.readMode
                controllerManager.operateMode = settings.operateMode
                controllerManager.setCapabilityPreferences(settings.capabilityPreferences)
                controllerManager.setDisabledModes(settings.disabledModes)
            }
        }
        appScope.launch {
            logSettingsRepository.settings.collect { logBus.minLevel = it.minLevel }
        }
        appScope.launch {
            logFileStore.trim()
            scheduleManager.rescheduleAll()
            subscriptionManager.rescheduleAll()
        }
        appScope.launch { updateManager.startIfAutoCheckEnabled() }
    }
}

/** 在任意处获取依赖容器。 */
val Context.appContainer: AppContainer
    get() = (applicationContext as IzukiApp).container
