package com.benton.izukijs.di

import android.app.Application
import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.controller.ScreenAnimationProbe
import com.benton.izukijs.controller.UiautomatorDumper
import com.benton.izukijs.controller.record.RecordingHooks
import com.benton.izukijs.controller.record.RecordingSession
import com.benton.izukijs.flow.FlowManager
import com.benton.izukijs.flow.FlowRunner
import com.benton.izukijs.flow.NotificationFlowNotifier
import com.benton.izukijs.runtime.RunPreparer
import com.benton.izukijs.runtime.ScriptExecutionManager
import com.benton.izukijs.runtime.api.DeviceApiBundle
import com.benton.izukijs.schedule.ScheduleManager
import com.benton.izukijs.schedule.ScheduleRunner
import com.benton.izukijs.schedule.TriggerConditions
import com.benton.izukijs.subscription.SubscriptionManager
import com.benton.izukijs.virtualscreen.ScreenTarget

/**
 * 脚本运行时图：执行管理器、录制、控件树入口，以及依赖运行时的定时 / 流程 / 订阅。
 *
 * 依赖 [CoreContainer] 与 [ControlContainer]；MCP 与 UI 只读这里，不反向被依赖。
 */
class RuntimeContainer(
    val application: Application,
    val core: CoreContainer,
    val control: ControlContainer,
) {

    /** 截图 / 应用启动的录制观察点，运行时内共享同一个实例。 */
    private val recordingHooks = RecordingHooks()

    /** 读控件树：无障碍优先，Shizuku / 无线调试 / Root 的 uiautomator dump 兜底。 */
    val nodeTreeProvider: (Long) -> NodeTreeResult = { timeoutMs ->
        UiautomatorDumper.read(
            control.controllerManager,
            core.logBus,
            timeoutMs,
            animationProbe = {
                ScreenAnimationProbe.isAnimating(
                    capture = { control.screenCapture.capture() },
                )
            },
        )
    }

    /** 显式主屏控件树：虚拟屏激活时仍按默认显示屏 dump，供 MCP target=main 使用。 */
    val mainNodeTreeProvider: (Long) -> NodeTreeResult = { timeoutMs ->
        UiautomatorDumper.read(
            control.controllerManager,
            core.logBus,
            timeoutMs,
            animationProbe = {
                ScreenAnimationProbe.isAnimating(
                    capture = { control.screenCapture.capture() },
                )
            },
            target = ScreenTarget.MAIN,
        )
    }

    /** 脚本与 MCP 共用的设备 API 集合；容器内只组装一次。 */
    val deviceApiBundle: DeviceApiBundle by lazy {
        DeviceApiBundle.create(
            application,
            control.controllerManager,
            control.screenCapture,
            control.ocrProcessor,
            core.logBus,
            appListEnabled = { core.runtimeSettingsRepository.current().appList },
            progressNotificationEnabled = {
                core.runtimeSettingsRepository.current().progressNotification
            },
            virtualScreen = control.virtualScreenManager,
            recordingHooks = recordingHooks,
        )
    }

    /** 控制操作录制；同一时间最多一个录制会话。 */
    val recordingSession = RecordingSession(
        context = application,
        controllers = control.controllerManager,
        logBus = core.logBus,
        hooks = recordingHooks,
        privileged = control.privilegedUnlockClient,
        captureProvider = { fresh -> deviceApiBundle.recordingCaptureProvider(fresh) },
        nodeTreeProvider = { timeoutMs -> nodeTreeProvider(timeoutMs) },
        foregroundProvider = { deviceApiBundle.appApi.foreground() },
    )

    val runPreparer = RunPreparer(
        appContext = application,
        controllers = control.controllerManager,
        logBus = core.logBus,
        aiConfigRepository = core.aiConfigRepository,
        httpSettingsRepository = core.httpSettingsRepository,
        scriptEnvRepository = core.scriptEnvRepository,
        globalEnvRepository = core.globalEnvRepository,
        runHistoryRepository = core.runHistoryRepository,
        virtualScreen = control.virtualScreenManager,
        virtualScreenSettings = core.virtualScreenSettingsRepository,
        runtimeSettings = core.runtimeSettingsRepository,
    )

    val scriptExecutionManager = ScriptExecutionManager(
        application,
        control.controllerManager,
        control.screenCapture,
        deviceApiBundle,
        nodeTreeProvider,
        core.logBus,
        core.aiConfigRepository,
        core.captureSettingsRepository,
        core.runtimeSettingsRepository,
        core.httpSettingsRepository,
        control.wakeLockController,
        control.screenUnlockController,
        control.virtualScreenManager,
        core.virtualScreenSettingsRepository,
        runPreparer,
        moduleResolver = core.scriptRepository,
        notificationDispatcher = core.notificationDispatcher,
        recordingSession = recordingSession,
    )

    val scheduleManager = ScheduleManager(application, core.scheduleRepository, core.logBus)

    val flowManager = FlowManager(application, core.flowRepository, core.logBus)

    val subscriptionManager = SubscriptionManager(
        application,
        core.subscriptionRepository,
        core.scriptRepository,
        core.logBus,
    )

    private val runConditions = TriggerConditions(
        application,
        foregroundProvider = { deviceApiBundle.appApi.currentPackage() },
    )

    val scheduleRunner = ScheduleRunner(
        repository = core.scheduleRepository,
        scriptRepository = core.scriptRepository,
        scheduleManager = scheduleManager,
        executionManager = scriptExecutionManager,
        screenUnlock = control.screenUnlockController,
        triggerConditions = runConditions,
        logBus = core.runLogSink,
    )

    val flowRunner = FlowRunner(
        repository = core.flowRepository,
        scriptRepository = core.scriptRepository,
        flowManager = flowManager,
        executionManager = scriptExecutionManager,
        screenUnlock = control.screenUnlockController,
        triggerConditions = runConditions,
        logBus = core.runLogSink,
        notificationDispatcher = NotificationFlowNotifier(
            application,
            core.notificationDispatcher,
            core.runLogSink,
        ),
    )
}
