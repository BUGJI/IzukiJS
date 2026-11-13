package com.benton.izukijs.di

import android.app.Application
import com.benton.izukijs.BuildConfig
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.record.RecordingSession
import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.permission.LocalNetworkPermission
import com.benton.izukijs.data.ScriptRepository
import com.benton.izukijs.mcp.McpConfigRepository
import com.benton.izukijs.mcp.McpServerController
import com.benton.izukijs.mcp.core.McpAuditLog
import com.benton.izukijs.mcp.resources.McpResources
import com.benton.izukijs.mcp.tools.McpToolRegistry
import com.benton.izukijs.runtime.LogFileStore
import com.benton.izukijs.runtime.ScriptExecutionManager
import com.benton.izukijs.runtime.api.DeviceApiBundle
import com.benton.izukijs.service.ScreenUnlockController
import com.benton.izukijs.service.UserRecordingService
import com.benton.izukijs.virtualscreen.VirtualScreenManager

/** MCP 服务相关的构造集中在这里，避免 [AppContainer] 继续膨胀。 */
class McpContainer(
    application: Application,
    controllerManager: ControllerManager,
    logBus: LogBus,
    private val deviceApiBundle: DeviceApiBundle,
    private val nodeTreeProvider: (timeoutMs: Long) -> NodeTreeResult,
    private val scriptRepository: ScriptRepository,
    private val scriptExecutionManager: ScriptExecutionManager,
    private val logFileStore: LogFileStore,
    private val virtualScreenManager: VirtualScreenManager,
    private val screenUnlockController: ScreenUnlockController,
    private val recordingSession: RecordingSession,
) {

    val configRepository = McpConfigRepository(application)

    val auditLog = McpAuditLog(application)

    val serverController = McpServerController(
        context = application,
        logBus = logBus,
        auditLog = auditLog,
        configProvider = { configRepository.current() },
        registryFactory = { operations ->
            McpToolRegistry(
                bundle = deviceApiBundle,
                nodeTreeProvider = nodeTreeProvider,
                scriptRepository = scriptRepository,
                executionManager = scriptExecutionManager,
                operations = operations,
                logFileStore = logFileStore,
                configProvider = { configRepository.current() },
                virtualScreen = virtualScreenManager,
                screenUnlock = screenUnlockController,
                recordingSession = recordingSession,
                userRecordingStarter = { options ->
                    // 与设置页共用前台服务，避免 MCP 启动的录制在后台被系统回收。
                    runCatching {
                        UserRecordingService.start(
                            context = application,
                            name = options.name,
                            captureScreenshots = options.captureScreenshots,
                            sampleIntervalMs = options.sampleIntervalMs,
                            captureContext = options.captureContext,
                        )
                        true
                    }.getOrDefault(false)
                },
                userRecordingStopper = {
                    runCatching { UserRecordingService.stop(application) }
                },
            )
        },
        resourcesFactory = {
            McpResources(
                bundle = deviceApiBundle,
                controllers = controllerManager,
                scriptRepository = scriptRepository,
                executionManager = scriptExecutionManager,
                logFileStore = logFileStore,
                auditLog = auditLog,
            )
        },
        serverVersion = BuildConfig.VERSION_NAME,
        localNetworkPermissionGranted = { LocalNetworkPermission.isGranted(application) },
    )
}
