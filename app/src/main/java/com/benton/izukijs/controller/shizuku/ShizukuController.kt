package com.benton.izukijs.controller.shizuku

import android.content.Context
import com.benton.izukijs.controller.shell.CommandDeviceController
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.runtime.LogBus

class ShizukuController(
    context: Context,
    shell: ShizukuShell,
    logBus: LogBus,
) : CommandDeviceController(context, shell, logBus) {

    override val mode: ControlMode = ControlMode.SHIZUKU
}
