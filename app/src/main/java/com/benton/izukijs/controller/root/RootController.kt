package com.benton.izukijs.controller.root

import android.content.Context
import com.benton.izukijs.controller.shell.CommandDeviceController
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.runtime.LogBus

class RootController(
    context: Context,
    shell: RootShell,
    logBus: LogBus,
) : CommandDeviceController(context, shell, logBus) {

    override val mode: ControlMode = ControlMode.ROOT

    override val supportedCapabilities: Set<Capability> =
        super.supportedCapabilities + Capability.ROOT
}
