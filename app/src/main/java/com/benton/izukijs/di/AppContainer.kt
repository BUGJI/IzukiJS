package com.benton.izukijs.di

import android.app.Application
import android.content.Context
import com.benton.izukijs.BuildConfig
import com.benton.izukijs.IzukiApp
import com.benton.izukijs.ai.AiConfigRepository
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.ControllerSettingsRepository
import com.benton.izukijs.controller.NodeSnapshot
import com.benton.izukijs.controller.UiautomatorDumper
import com.benton.izukijs.controller.hid.HidManager
import com.benton.izukijs.controller.root.RootManager
import com.benton.izukijs.controller.shizuku.ShizukuManager
import com.benton.izukijs.model.Capability
import com.benton.izukijs.data.AppearanceRepository
import com.benton.izukijs.data.ConfigBackupManager
import com.benton.izukijs.data.EditorSettingsRepository
import com.benton.izukijs.data.RunHistoryRepository
import com.benton.izukijs.data.ScriptEnvRepository
import com.benton.izukijs.data.ScriptImporter
import com.benton.izukijs.data.ScriptListPreferences
import com.benton.izukijs.data.ScriptRepository
import com.benton.izukijs.i18n.LanguageRepository
import com.benton.izukijs.mcp.McpConfigRepository
import com.benton.izukijs.mcp.McpServerController
import com.benton.izukijs.mcp.resources.McpResources
import com.benton.izukijs.mcp.tools.McpToolRegistry
import com.benton.izukijs.ocr.OcrConfigRepository
import com.benton.izukijs.ocr.OcrProcessor
import com.benton.izukijs.runtime.LogBus
import com.benton.izukijs.runtime.LogFileStore
import com.benton.izukijs.runtime.LogSettingsRepository
import com.benton.izukijs.runtime.ScriptExecutionManager
import com.benton.izukijs.runtime.api.DeviceApiBundle
import com.benton.izukijs.schedule.ScheduleManager
import com.benton.izukijs.schedule.ScheduleRepository
import com.benton.izukijs.service.CaptureSettingsRepository
import com.benton.izukijs.service.ScreenCapture
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

    val runHistoryRepository = RunHistoryRepository(application)

    val screenCapture = ScreenCapture(logBus)

    val ocrConfigRepository = OcrConfigRepository(application)

    val ocrProcessor = OcrProcessor(ocrConfigRepository, captureSettingsRepository, logBus)

    val aiConfigRepository = AiConfigRepository(application)

    val editorSettingsRepository = EditorSettingsRepository(application)

    val languageRepository = LanguageRepository(application)

    val appearanceRepository = AppearanceRepository(application)

    val configBackupManager = ConfigBackupManager(
        aiConfigRepository = aiConfigRepository,
        ocrConfigRepository = ocrConfigRepository,
        controllerSettingsRepository = controllerSettingsRepository,
        captureSettingsRepository = captureSettingsRepository,
        logSettingsRepository = logSettingsRepository,
        editorSettingsRepository = editorSettingsRepository,
        appearanceRepository = appearanceRepository,
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
        runHistoryRepository,
        moduleSourceProvider = { raw ->
            val id = raw.trim().removePrefix("./").removeSuffix(".js")
            scriptRepository.find(id)?.let { scriptRepository.read(it) }
        },
    )

    val mcpConfigRepository = McpConfigRepository(application)

    /** 脚本与 MCP 共用的设备 API 集合；懒加载，首次使用时组装。 */
    private val deviceApiBundle: DeviceApiBundle by lazy {
        DeviceApiBundle.create(application, controllerManager, screenCapture, ocrProcessor, logBus)
    }

    private val uiautomatorDumper = UiautomatorDumper(controllerManager, logBus)

    /** 读控件树：无障碍优先，Shizuku / Root 的 uiautomator dump 兜底。 */
    private val nodeTreeProvider: () -> NodeSnapshot? = {
        controllerManager.controllerFor(Capability.NODE_TREE)?.nodeTree() ?: uiautomatorDumper.dump()
    }

    val mcpServerController = McpServerController(
        logBus = logBus,
        configProvider = { mcpConfigRepository.current() },
        registryFactory = { config, operations ->
            McpToolRegistry(
                bundle = deviceApiBundle,
                nodeTreeProvider = nodeTreeProvider,
                scriptRepository = scriptRepository,
                executionManager = scriptExecutionManager,
                operations = operations,
                allowShell = config.allowShell,
                allowScripts = config.allowScripts,
                allowDangerousShell = config.allowDangerousShell,
                isPackageBlocked = { config.isPackageBlocked(it) },
            )
        },
        resourcesFactory = {
            McpResources(
                bundle = deviceApiBundle,
                controllers = controllerManager,
                scriptRepository = scriptRepository,
                executionManager = scriptExecutionManager,
                logFileStore = logFileStore,
            )
        },
        serverVersion = BuildConfig.VERSION_NAME,
    )

    val shizukuManager = ShizukuManager(application, controllerManager, logBus)

    val rootManager = RootManager(application, controllerManager, logBus, appScope)

    val hidManager = HidManager(application, controllerManager, logBus, appScope)

    val scheduleRepository = ScheduleRepository(application)

    val scheduleManager = ScheduleManager(application, scheduleRepository, logBus)

    /** 初始化控制后端。会注册 Shizuku 监听并异步探测 Root。 */
    fun init() {
        logBus.setSink { logFileStore.append(it) }
        appScope.launch { scriptRepository.seedBundledScripts() }
        shizukuManager.init()
        rootManager.init()
        hidManager.init()
        // 控制偏好以持久化的 Repository 为唯一数据源，实时同步到内存中的 ControllerManager。
        appScope.launch {
            controllerSettingsRepository.settings.collect { settings ->
                controllerManager.preferredMode = settings.preferredMode
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
        }
    }
}

/** 在任意处获取依赖容器。 */
val Context.appContainer: AppContainer
    get() = (applicationContext as IzukiApp).container
