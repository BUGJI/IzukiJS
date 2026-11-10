package com.benton.izukijs.di

import android.app.Application
import android.content.Context
import com.benton.izukijs.IzukiApp
import com.benton.izukijs.ai.AiConfigRepository
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.ControllerSettingsRepository
import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.controller.UiautomatorDumper
import com.benton.izukijs.controller.adb.AdbManager
import com.benton.izukijs.controller.hid.HidManager
import com.benton.izukijs.controller.root.RootManager
import com.benton.izukijs.controller.shizuku.ShizukuManager
import com.benton.izukijs.data.AppearanceRepository
import com.benton.izukijs.backup.ConfigBackupManager
import com.benton.izukijs.backup.ConfigSections
import com.benton.izukijs.data.EditorSettingsRepository
import com.benton.izukijs.data.GlobalEnvRepository
import com.benton.izukijs.data.RunHistoryRepository
import com.benton.izukijs.data.ScriptEnvRepository
import com.benton.izukijs.data.ScriptImporter
import com.benton.izukijs.data.ScriptListPreferences
import com.benton.izukijs.data.ScriptRepository
import com.benton.izukijs.data.TileSettingsRepository
import com.benton.izukijs.data.UnlockSettingsRepository
import com.benton.izukijs.flow.FlowManager
import com.benton.izukijs.flow.FlowRepository
import com.benton.izukijs.flow.FlowRunner
import com.benton.izukijs.i18n.LanguageRepository
import com.benton.izukijs.ocr.OcrConfigRepository
import com.benton.izukijs.ocr.OcrProcessor
import com.benton.izukijs.runtime.HttpSettingsRepository
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.perf.PerfMetrics
import com.benton.izukijs.core.runtime.RunControl
import com.benton.izukijs.runtime.LogFileStore
import com.benton.izukijs.runtime.LogSettingsRepository
import com.benton.izukijs.runtime.RunPreparer
import com.benton.izukijs.runtime.RuntimeSettingsRepository
import com.benton.izukijs.runtime.ScriptExecutionManager
import com.benton.izukijs.runtime.api.DeviceApiBundle
import com.benton.izukijs.schedule.ScheduleManager
import com.benton.izukijs.schedule.ScheduleRepository
import com.benton.izukijs.schedule.ScheduleRunner
import com.benton.izukijs.privileged.PrivilegedUnlockClient
import com.benton.izukijs.service.CaptureSettingsRepository
import com.benton.izukijs.service.ScreenCapture
import com.benton.izukijs.service.ScreenUnlockController
import com.benton.izukijs.service.UnlockGestureRecorder
import com.benton.izukijs.service.WakeLockController
import com.benton.izukijs.subscription.SubscriptionManager
import com.benton.izukijs.subscription.SubscriptionRepository
import com.benton.izukijs.update.UpdateManager
import com.benton.izukijs.update.UpdateSettingsRepository
import com.benton.izukijs.virtualscreen.VirtualScreenManager
import com.benton.izukijs.virtualscreen.VirtualScreenSettingsRepository
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

    val virtualScreenSettingsRepository = VirtualScreenSettingsRepository(application)

    val virtualScreenManager = VirtualScreenManager(
        context = application,
        controllers = controllerManager,
        settingsRepository = virtualScreenSettingsRepository,
        logBus = logBus,
    )

    val httpSettingsRepository = HttpSettingsRepository(application)

    val wakeLockController = WakeLockController(application)

    val unlockSettingsRepository = UnlockSettingsRepository(application)

    val privilegedUnlockClient = PrivilegedUnlockClient(application, logBus)

    val screenUnlockController = ScreenUnlockController(
        context = application,
        controllers = controllerManager,
        settingsRepository = unlockSettingsRepository,
        wakeLock = wakeLockController,
        privileged = privilegedUnlockClient,
        logBus = logBus,
    )

    val unlockGestureRecorder = UnlockGestureRecorder(
        context = application,
        controllers = controllerManager,
        settingsRepository = unlockSettingsRepository,
        wakeLock = wakeLockController,
        privileged = privilegedUnlockClient,
        logBus = logBus,
    )

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
        sections = ConfigSections.all(
            ai = aiConfigRepository,
            ocr = ocrConfigRepository,
            controller = controllerSettingsRepository,
            capture = captureSettingsRepository,
            log = logSettingsRepository,
            http = httpSettingsRepository,
            editor = editorSettingsRepository,
            appearance = appearanceRepository,
            update = updateSettingsRepository,
            globalEnv = globalEnvRepository,
            virtualScreen = virtualScreenSettingsRepository,
        ),
        // 锁屏 PIN / 图案使用设备绑定密钥加密，不参与导出 / 导入，仅在重置时清理。
        resetOnly = listOf(unlockSettingsRepository::clear),
    )

    /** 脚本与 MCP 共用的设备 API 集合；容器内只组装一次。 */
    val deviceApiBundle: DeviceApiBundle by lazy {
        DeviceApiBundle.create(
            application,
            controllerManager,
            screenCapture,
            ocrProcessor,
            logBus,
            appListEnabled = { runtimeSettingsRepository.current().appList },
            progressNotificationEnabled = {
                runtimeSettingsRepository.current().progressNotification
            },
            virtualScreen = virtualScreenManager,
        )
    }

    /** 读控件树：无障碍优先，Shizuku / 无线调试 / Root 的 uiautomator dump 兜底。 */
    private val nodeTreeProvider: (Long) -> NodeTreeResult = { timeoutMs ->
        UiautomatorDumper.read(
            controllerManager,
            logBus,
            timeoutMs,
        ) {
            com.benton.izukijs.controller.ScreenAnimationProbe.isAnimating(
                capture = { screenCapture.capture() },
            )
        }
    }

    private val runPreparer = RunPreparer(
        appContext = application,
        controllers = controllerManager,
        logBus = logBus,
        aiConfigRepository = aiConfigRepository,
        httpSettingsRepository = httpSettingsRepository,
        scriptEnvRepository = scriptEnvRepository,
        globalEnvRepository = globalEnvRepository,
        runHistoryRepository = runHistoryRepository,
        virtualScreen = virtualScreenManager,
        virtualScreenSettings = virtualScreenSettingsRepository,
        runtimeSettings = runtimeSettingsRepository,
    )

    val scriptExecutionManager = ScriptExecutionManager(
        application,
        controllerManager,
        screenCapture,
        deviceApiBundle,
        nodeTreeProvider,
        logBus,
        aiConfigRepository,
        captureSettingsRepository,
        runtimeSettingsRepository,
        httpSettingsRepository,
        wakeLockController,
        screenUnlockController,
        virtualScreenManager,
        virtualScreenSettingsRepository,
        runPreparer,
        moduleSourceProvider = { raw ->
            val id = raw.trim().removePrefix("./").removeSuffix(".js")
            scriptRepository.find(id)?.let { scriptRepository.read(it) }
        },
    )

    /** 面向通知 / 悬浮窗的运行控制端口，避免 service 反向依赖 runtime 实现。 */
    val runControl: RunControl get() = scriptExecutionManager

    private val mcp = McpContainer(
        application = application,
        controllerManager = controllerManager,
        logBus = logBus,
        deviceApiBundle = deviceApiBundle,
        nodeTreeProvider = nodeTreeProvider,
        scriptRepository = scriptRepository,
        scriptExecutionManager = scriptExecutionManager,
        logFileStore = logFileStore,
        virtualScreenManager = virtualScreenManager,
        screenUnlockController = screenUnlockController,
    )

    val mcpConfigRepository get() = mcp.configRepository
    val mcpAuditLog get() = mcp.auditLog
    val mcpServerController get() = mcp.serverController

    val shizukuManager = ShizukuManager(application, controllerManager, logBus)

    val rootManager = RootManager(application, controllerManager, logBus, appScope)

    val hidManager = HidManager(application, controllerManager, logBus, appScope)

    val adbManager = AdbManager(application, controllerManager, logBus, appScope)

    val scheduleRepository = ScheduleRepository(application)

    val scheduleManager = ScheduleManager(application, scheduleRepository, logBus)

    val flowRepository = FlowRepository(application)

    val flowManager = FlowManager(application, flowRepository, logBus)

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
        screenUnlock = screenUnlockController,
        foregroundProvider = { deviceApiBundle.appApi.currentPackage() },
        logBus = logBus,
    )

    val flowRunner = FlowRunner(
        context = application,
        repository = flowRepository,
        scriptRepository = scriptRepository,
        flowManager = flowManager,
        executionManager = scriptExecutionManager,
        screenUnlock = screenUnlockController,
        foregroundProvider = { deviceApiBundle.appApi.currentPackage() },
        logBus = logBus,
    )

    /** 初始化控制后端。会注册 Shizuku 监听并异步探测 Root。 */
    fun init() {
        logBus.setSink { logFileStore.append(it) }
        PerfMetrics.setReportSink { logBus.info(it) }
        appScope.launch { scriptRepository.seedBundledScripts() }
        shizukuManager.init()
        rootManager.init()
        hidManager.init()
        adbManager.init()
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
            runtimeSettingsRepository.settings.collect { PerfMetrics.setEnabled(it.perfBaseline) }
        }
        appScope.launch {
            logFileStore.trim()
            scheduleManager.rescheduleAll()
            flowManager.rescheduleAll()
            subscriptionManager.rescheduleAll()
        }
        appScope.launch { updateManager.startIfAutoCheckEnabled() }
    }
}

/** 在任意处获取依赖容器。 */
val Context.appContainer: AppContainer
    get() = (applicationContext as IzukiApp).container
