package com.benton.izukijs.controller.adb

import android.content.Context
import com.benton.izukijs.controller.shell.CommandDeviceController
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.model.ControlMode

/**
 * 无线调试控制后端。与 Shizuku / Root 一样通过 adb shell 执行命令，
 * 仅底层 [AdbShell] 的传输方式不同。
 */
class AdbController(
    context: Context,
    shell: AdbShell,
    logBus: LogBus,
    displayIdProvider: () -> Int? = { null },
) : CommandDeviceController(context, shell, logBus, displayIdProvider) {

    override val mode: ControlMode = ControlMode.ADB
}
