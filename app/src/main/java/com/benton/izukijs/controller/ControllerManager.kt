package com.benton.izukijs.controller

import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 控制模式注册中心。按「能力」而非整体选择后端，支持混合模式。
 *
 * 例如：读取控件树走无障碍，而绝对坐标点击走已连接的 HID 数位板。
 */
class ControllerManager {

    private val controllers = CopyOnWriteArrayList<DeviceController>()

    private val _readyModes = MutableStateFlow<Set<ControlMode>>(emptySet())
    val readyModes: StateFlow<Set<ControlMode>> = _readyModes.asStateFlow()

    /** 全局优先使用的模式；为空时按注册顺序。 */
    @Volatile
    var preferredMode: ControlMode? = null

    /** 按能力单独指定的优先模式，优先级高于 [preferredMode]。 */
    private val capabilityPreferences = ConcurrentHashMap<Capability, ControlMode>()

    /** 被停用的后端，不参与能力协商（服务连接本身不受影响）。 */
    @Volatile
    private var disabledModes: Set<ControlMode> = emptySet()

    /** 用持久化的偏好覆盖当前内存状态（在 App 启动与设置变更时调用）。 */
    fun setCapabilityPreferences(preferences: Map<Capability, ControlMode>) {
        capabilityPreferences.clear()
        capabilityPreferences.putAll(preferences)
    }

    fun setDisabledModes(modes: Set<ControlMode>) {
        disabledModes = modes
    }

    fun isDisabled(mode: ControlMode): Boolean = mode in disabledModes

    /** 某项能力实际生效的优先模式：能力级偏好优先，其次全局。 */
    fun preferredFor(capability: Capability): ControlMode? =
        capabilityPreferences[capability] ?: preferredMode

    /** 当前已注册后端各自声明提供的能力，供设置界面展示可选项。 */
    fun capabilitiesByMode(): Map<ControlMode, Set<Capability>> =
        controllers.associate { it.mode to it.capabilities() }

    fun register(controller: DeviceController) {
        controllers.removeAll { it.mode == controller.mode }
        controllers.add(controller)
        refresh()
    }

    fun unregister(controller: DeviceController) {
        controllers.remove(controller)
        refresh()
    }

    fun unregister(mode: ControlMode) {
        controllers.removeAll { it.mode == mode }
        refresh()
    }

    fun refresh() {
        _readyModes.value = controllers.filter { it.isReady() }.map { it.mode }.toSet()
    }

    fun all(): List<DeviceController> = controllers.toList()

    /**
     * 为某项能力选择一个已就绪的控制后端，优先使用该能力的偏好模式，其次全局偏好。
     */
    fun controllerFor(capability: Capability): DeviceController? {
        val ordered = order(capability)
        return ordered.firstOrNull {
            !isDisabled(it.mode) && it.isReady() && capability in it.capabilities()
        }
    }

    /** 只要有一个（未被停用的）后端就绪即视为可用。 */
    fun anyReady(): Boolean = controllers.any { !isDisabled(it.mode) && it.isReady() }

    private fun order(capability: Capability): List<DeviceController> {
        val preferred = preferredFor(capability) ?: return controllers.toList()
        return controllers.sortedBy { if (it.mode == preferred) 0 else 1 }
    }
}
