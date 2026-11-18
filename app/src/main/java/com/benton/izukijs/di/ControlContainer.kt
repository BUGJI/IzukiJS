package com.benton.izukijs.di

import android.app.Application
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.adb.AdbManager
import com.benton.izukijs.controller.hid.HidManager
import com.benton.izukijs.controller.root.RootManager
import com.benton.izukijs.controller.shizuku.ShizukuManager
import com.benton.izukijs.ocr.OcrProcessor
import com.benton.izukijs.privileged.PrivilegedUnlockClient
import com.benton.izukijs.service.ScreenCapture
import com.benton.izukijs.service.ScreenUnlockController
import com.benton.izukijs.service.UnlockGestureRecorder
import com.benton.izukijs.service.WakeLockController
import com.benton.izukijs.virtualscreen.VirtualScreenManager

/**
 * 控制后端与设备能力图：控制模式注册、截图、OCR、解锁、虚拟屏。
 *
 * 依赖 [CoreContainer] 的仓库与日志，但不依赖脚本运行时，避免 `controller <-> runtime` 循环。
 */
class ControlContainer(
    val application: Application,
    val core: CoreContainer,
) {

    val controllerManager = ControllerManager()

    val virtualScreenManager = VirtualScreenManager(
        context = application,
        controllers = controllerManager,
        settingsRepository = core.virtualScreenSettingsRepository,
        logBus = core.logBus,
    )

    val wakeLockController = WakeLockController(application)

    val privilegedUnlockClient = PrivilegedUnlockClient(application, core.logBus)

    val screenUnlockController = ScreenUnlockController(
        context = application,
        controllers = controllerManager,
        settingsRepository = core.unlockSettingsRepository,
        wakeLock = wakeLockController,
        privileged = privilegedUnlockClient,
        logBus = core.logBus,
    )

    val unlockGestureRecorder = UnlockGestureRecorder(
        context = application,
        controllers = controllerManager,
        settingsRepository = core.unlockSettingsRepository,
        wakeLock = wakeLockController,
        privileged = privilegedUnlockClient,
        logBus = core.logBus,
    )

    val screenCapture = ScreenCapture(core.logBus)

    val ocrProcessor = OcrProcessor(
        core.ocrConfigRepository,
        core.captureSettingsRepository,
        core.logBus,
    )

    val shizukuManager = ShizukuManager(application, controllerManager, core.logBus)

    val rootManager = RootManager(application, controllerManager, core.logBus, core.appScope)

    val hidManager = HidManager(application, controllerManager, core.logBus, core.appScope)

    val adbManager = AdbManager(application, controllerManager, core.logBus, core.appScope)
}
