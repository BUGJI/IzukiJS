package com.benton.izukijs.testing

import com.benton.izukijs.controller.DeviceController
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode

/**
 * 可配置的控制后端替身，用于能力协商测试。
 *
 * 只实现协商相关的最小成员；点击/截图等默认实现保持无副作用，避免测试误触 Android 类。
 */
class FakeDeviceController(
    override val mode: ControlMode,
    private var ready: Boolean = true,
    private var capabilities: Set<Capability> = emptySet(),
    private val displayTarget: Boolean = false,
) : DeviceController {

    var readyChecks: Int = 0
        private set

    override fun capabilities(): Set<Capability> = capabilities

    override fun isReady(): Boolean {
        readyChecks++
        return ready
    }

    override fun supportsDisplayTarget(): Boolean = displayTarget

    fun setReady(value: Boolean) {
        ready = value
    }

    fun setCapabilities(value: Set<Capability>) {
        capabilities = value
    }
}
