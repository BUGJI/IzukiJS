package com.benton.izukijs.di

import android.app.Application
import android.content.Context
import com.benton.izukijs.IzukiApp
import com.benton.izukijs.backup.ConfigBackupManager
import com.benton.izukijs.backup.ConfigSections
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.ControllerSettingsRepository
import com.benton.izukijs.controller.adb.AdbManager
import com.benton.izukijs.controller.hid.HidManager
import com.benton.izukijs.controller.record.RecordingSession
import com.benton.izukijs.controller.root.RootManager
import com.benton.izukijs.controller.shizuku.ShizukuManager
import com.benton.izukijs.core.config.RuntimeLimits
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.perf.PerfMetrics
import com.benton.izukijs.core.runtime.RunControl
import com.benton.izukijs.developer.DeveloperTuning
import com.benton.izukijs.notification.NotificationDispatcher
import com.benton.izukijs.runtime.ScriptExecutionManager
import com.benton.izukijs.service.McpServerService
import com.benton.izukijs.service.ScreenCapture
import com.benton.izukijs.service.ScreenUnlockController
import com.benton.izukijs.virtualscreen.VirtualScreenManager
import kotlinx.coroutines.launch

/**
 * 应用依赖门面。
 *
 * 真正的构造已拆到 [CoreContainer] / [ControlContainer] / [RuntimeContainer] / [McpContainer]；
 * 这里保留原有属性名作为兼容层，调用点可逐步改为 `container.core.xxx` 等具名图。
 * 由 `IzukiApp` 通过 Koin 注入并调用一次 [init]。
 */
class AppContainer(
    val application: Application,
    val core: CoreContainer,
    val control: ControlContainer,
    val runtime: RuntimeContainer,
    private val mcp: McpContainer,
) {

    // ---- 作用域 ----
    val uiScope get() = core.uiScope

    // ---- 基础 / 仓库 ----
    val logBus: LogBus get() = core.logBus
    val logSettingsRepository get() = core.logSettingsRepository
    val logFileStore get() = core.logFileStore
    val cacheImageStore get() = core.cacheImageStore
    val scriptRepository get() = core.scriptRepository
    val scriptStorageRepository get() = core.scriptStorageRepository
    val scriptImporter get() = core.scriptImporter
    val scriptListPreferences get() = core.scriptListPreferences
    val scriptEnvRepository get() = core.scriptEnvRepository
    val globalEnvRepository get() = core.globalEnvRepository
    val runHistoryRepository get() = core.runHistoryRepository
    val tileSettingsRepository get() = core.tileSettingsRepository
    val runtimeSettingsRepository get() = core.runtimeSettingsRepository
    val notificationSettingsRepository get() = core.notificationSettingsRepository
    val notificationDispatcher: NotificationDispatcher get() = core.notificationDispatcher
    val virtualScreenSettingsRepository get() = core.virtualScreenSettingsRepository
    val httpSettingsRepository get() = core.httpSettingsRepository
    val unlockSettingsRepository get() = core.unlockSettingsRepository
    val ocrConfigRepository get() = core.ocrConfigRepository
    val aiConfigRepository get() = core.aiConfigRepository
    val editorSettingsRepository get() = core.editorSettingsRepository
    val languageRepository get() = core.languageRepository
    val appearanceRepository get() = core.appearanceRepository
    val developerSettingsRepository get() = core.developerSettingsRepository
    val updateSettingsRepository get() = core.updateSettingsRepository
    val updateManager get() = core.updateManager
    val scheduleRepository get() = core.scheduleRepository
    val flowRepository get() = core.flowRepository
    val subscriptionRepository get() = core.subscriptionRepository
    val controllerSettingsRepository: ControllerSettingsRepository get() = core.controllerSettingsRepository
    val captureSettingsRepository get() = core.captureSettingsRepository

    // ---- 控制 / 设备能力 ----
    val controllerManager: ControllerManager get() = control.controllerManager
    val virtualScreenManager: VirtualScreenManager get() = control.virtualScreenManager
    val wakeLockController get() = control.wakeLockController
    val privilegedUnlockClient get() = control.privilegedUnlockClient
    val screenUnlockController: ScreenUnlockController get() = control.screenUnlockController
    val unlockGestureRecorder get() = control.unlockGestureRecorder
    val screenCapture: ScreenCapture get() = control.screenCapture
    val ocrProcessor get() = control.ocrProcessor
    val shizukuManager: ShizukuManager get() = control.shizukuManager
    val rootManager: RootManager get() = control.rootManager
    val hidManager: HidManager get() = control.hidManager
    val adbManager: AdbManager get() = control.adbManager

    // ---- 运行时 ----
    val deviceApiBundle get() = runtime.deviceApiBundle
    val recordingSession: RecordingSession get() = runtime.recordingSession
    val scriptExecutionManager: ScriptExecutionManager get() = runtime.scriptExecutionManager
    val runControl: RunControl get() = runtime.scriptExecutionManager
    val scheduleManager get() = runtime.scheduleManager
    val flowManager get() = runtime.flowManager
    val subscriptionManager get() = runtime.subscriptionManager
    val scheduleRunner get() = runtime.scheduleRunner
    val flowRunner get() = runtime.flowRunner

    // ---- MCP ----
    val mcpConfigRepository get() = mcp.configRepository
    val mcpAuditLog get() = mcp.auditLog
    val mcpServerController get() = mcp.serverController

    val configBackupManager by lazy {
        ConfigBackupManager(
            sections = ConfigSections.all(
                ai = core.aiConfigRepository,
                ocr = core.ocrConfigRepository,
                controller = core.controllerSettingsRepository,
                capture = core.captureSettingsRepository,
                log = core.logSettingsRepository,
                http = core.httpSettingsRepository,
                editor = core.editorSettingsRepository,
                appearance = core.appearanceRepository,
                update = core.updateSettingsRepository,
                globalEnv = core.globalEnvRepository,
                virtualScreen = core.virtualScreenSettingsRepository,
                notification = core.notificationSettingsRepository,
                runtime = core.runtimeSettingsRepository,
                language = core.languageRepository,
                developer = core.developerSettingsRepository,
                mcp = mcp.configRepository,
                tile = core.tileSettingsRepository,
            ),
            // 锁屏 PIN / 图案使用设备绑定密钥加密，不参与导出 / 导入，仅在重置时清理。
            resetOnly = listOf(core.unlockSettingsRepository::clear),
            // 导入 / 重置可能关闭 MCP 或改掉令牌、端口；已运行的监听必须同步重建或停止。
            onApplied = {
                val config = mcp.configRepository.current()
                runCatching {
                    if (config.enabled && config.isConfigured) {
                        McpServerService.restart(application)
                    } else {
                        McpServerService.stop(application)
                    }
                }
            },
        )
    }

    /** 初始化控制后端。会注册 Shizuku 监听并异步探测 Root。 */
    fun init() {
        core.logBus.setSink { core.logFileStore.append(it) }
        PerfMetrics.setReportSink { core.logBus.info(it) }
        val developerSettings = core.developerSettingsRepository.current()
        DeveloperTuning.update(developerSettings)
        RuntimeLimits.maxScriptBytes = developerSettings.maxScriptBytes
        core.appScope.launch { core.scriptRepository.seedBundledScripts() }
        control.shizukuManager.init()
        control.rootManager.init()
        control.hidManager.init()
        control.adbManager.init()
        runtime.scheduleManager.start()
        // 控制偏好以持久化的 Repository 为唯一数据源，实时同步到内存中的 ControllerManager。
        core.appScope.launch {
            core.controllerSettingsRepository.settings.collect { settings ->
                control.controllerManager.readMode = settings.readMode
                control.controllerManager.operateMode = settings.operateMode
                control.controllerManager.setCapabilityPreferences(settings.capabilityPreferences)
                control.controllerManager.setDisabledModes(settings.disabledModes)
            }
        }
        core.appScope.launch {
            core.logSettingsRepository.settings.collect { core.logBus.minLevel = it.minLevel }
        }
        core.appScope.launch {
            core.runtimeSettingsRepository.settings.collect {
                PerfMetrics.setEnabled(it.perfBaseline)
            }
        }
        core.appScope.launch {
            core.developerSettingsRepository.settings.collect {
                DeveloperTuning.update(it)
                // core 层只读这份快照，避免 core -> developer 的反向依赖。
                RuntimeLimits.maxScriptBytes = it.maxScriptBytes
            }
        }
        core.appScope.launch {
            core.logFileStore.trim()
            runtime.scheduleManager.rescheduleAll()
            runtime.flowManager.rescheduleAll()
            runtime.subscriptionManager.rescheduleAll()
        }
        core.appScope.launch { core.updateManager.startIfAutoCheckEnabled() }
    }
}

/** 在任意处获取依赖门面。 */
val Context.appContainer: AppContainer
    get() = (applicationContext as IzukiApp).container
