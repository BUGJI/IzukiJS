package com.benton.izukijs.controller.root

import android.content.Context
import com.benton.izukijs.controller.shell.CommandDeviceController
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode

class RootController(
    context: Context,
    shell: RootShell,
) : CommandDeviceController(context, shell) {

    override val mode: ControlMode = ControlMode.ROOT

    override val supportedCapabilities: Set<Capability> =
        super.supportedCapabilities + Capability.ROOT
}
