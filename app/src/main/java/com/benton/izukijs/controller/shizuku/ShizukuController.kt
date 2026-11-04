package com.benton.izukijs.controller.shizuku

import android.content.Context
import com.benton.izukijs.controller.shell.CommandDeviceController
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.core.log.LogBus

class ShizukuController(
    context: Context,
    shell: ShizukuShell,
    logBus: LogBus,
    displayIdProvider: () -> Int? = { null },
) : CommandDeviceController(context, shell, logBus, displayIdProvider) {

    override val mode: ControlMode = ControlMode.SHIZUKU
}
