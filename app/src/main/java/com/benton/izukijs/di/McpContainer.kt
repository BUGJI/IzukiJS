package com.benton.izukijs.di

import android.app.Application
import com.benton.izukijs.BuildConfig
import com.benton.izukijs.core.permission.LocalNetworkPermission
import com.benton.izukijs.mcp.McpConfigRepository
import com.benton.izukijs.mcp.McpServerController
import com.benton.izukijs.mcp.core.McpAuditLog
import com.benton.izukijs.mcp.resources.McpDocCatalog
import com.benton.izukijs.mcp.resources.McpResources
import com.benton.izukijs.mcp.tools.McpToolRegistry
import com.benton.izukijs.service.UserRecordingService

/**
 * MCP 服务相关的构造集中在这里。
 *
 * 只读取 [CoreContainer] / [ControlContainer] / [RuntimeContainer] 的公开依赖，
 * 不再逐个传 12 个参数，也避免反向依赖 UI 或服务实现。
 */
class McpContainer(
    private val application: Application,
    private val core: CoreContainer,
    private val control: ControlContainer,
    private val runtime: RuntimeContainer,
) {

    val configRepository = McpConfigRepository(application)

    val auditLog = McpAuditLog(application)

    /**
     * 仓库 docs/ 在构建期被复制到 assets/docs（见 app/build.gradle.kts），
     * 这里只负责按名字读取；索引与分页由 [McpDocCatalog] 处理。
     */
    private val docsCatalog = McpDocCatalog(
        assetNames = {
            runCatching { application.assets.list(DOCS_ASSET_DIR)?.toList() }
                .getOrNull()
                .orEmpty()
        },
        assetReader = { name ->
            runCatching {
                application.assets.open("$DOCS_ASSET_DIR/$name")
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            }.getOrNull()
        },
    )

    val serverController = McpServerController(
        context = application,
        logBus = core.logBus,
        auditLog = auditLog,
        configProvider = { configRepository.current() },
        registryFactory = { operations ->
            McpToolRegistry(
                bundle = runtime.deviceApiBundle,
                nodeTreeProvider = runtime.nodeTreeProvider,
                mainNodeTreeProvider = runtime.mainNodeTreeProvider,
                scriptRepository = core.scriptRepository,
                executionManager = runtime.scriptExecutionManager,
                operations = operations,
                logFileStore = core.logFileStore,
                configProvider = { configRepository.current() },
                virtualScreen = control.virtualScreenManager,
                screenUnlock = control.screenUnlockController,
                recordingSession = runtime.recordingSession,
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
                docs = docsCatalog,
            )
        },
        resourcesFactory = {
            McpResources(
                bundle = runtime.deviceApiBundle,
                controllers = control.controllerManager,
                scriptRepository = core.scriptRepository,
                executionManager = runtime.scriptExecutionManager,
                logFileStore = core.logFileStore,
                auditLog = auditLog,
                docs = docsCatalog,
            )
        },
        serverVersion = BuildConfig.VERSION_NAME,
        localNetworkPermissionGranted = { LocalNetworkPermission.isGranted(application) },
    )

    private companion object {
        const val DOCS_ASSET_DIR = "docs"
    }
}
