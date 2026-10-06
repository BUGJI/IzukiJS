package com.benton.izukijs.controller.shizuku

import android.content.Context
import com.benton.izukijs.controller.shell.CommandDeviceController
import com.benton.izukijs.model.ControlMode

class ShizukuController(
    context: Context,
    shell: ShizukuShell,
) : CommandDeviceController(context, shell) {

    override val mode: ControlMode = ControlMode.SHIZUKU
}
