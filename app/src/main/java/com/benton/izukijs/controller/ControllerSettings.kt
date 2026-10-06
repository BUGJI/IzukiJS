package com.benton.izukijs.controller

import android.content.Context
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 控制后端偏好。除全局优先模式外，可为单项能力单独指定优先模式，
 * 例如「读控件树走无障碍、点击走 HID」的混合模式。
 */
data class ControllerSettings(
    val preferredMode: ControlMode? = null,
    val capabilityPreferences: Map<Capability, ControlMode> = emptyMap(),
    /** 被停用的后端不参与能力协商（服务连接本身不受影响）。 */
    val disabledModes: Set<ControlMode> = emptySet(),
)

/** SharedPreferences 持久化 + 内存缓存，作为控制偏好的唯一数据源。 */
class ControllerSettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ControllerSettings> = _settings.asStateFlow()

    fun current(): ControllerSettings = _settings.value

    fun save(value: ControllerSettings) {
        _settings.value = value
        prefs.edit().apply {
            if (value.preferredMode == null) {
                remove(KEY_PREFERRED)
            } else {
                putString(KEY_PREFERRED, value.preferredMode.name)
            }
            Capability.entries.forEach { capability ->
                val key = capabilityKey(capability)
                val mode = value.capabilityPreferences[capability]
                if (mode == null) remove(key) else putString(key, mode.name)
            }
            putStringSet(KEY_DISABLED, value.disabledModes.map { it.name }.toSet())
        }.apply()
    }

    private fun load(): ControllerSettings {
        val preferred = prefs.getString(KEY_PREFERRED, null)?.toControlMode()
        val preferences = buildMap {
            Capability.entries.forEach { capability ->
                prefs.getString(capabilityKey(capability), null)?.toControlMode()?.let {
                    put(capability, it)
                }
            }
        }
        val disabled = prefs.getStringSet(KEY_DISABLED, emptySet())
            .orEmpty()
            .mapNotNull { name -> runCatching { ControlMode.valueOf(name) }.getOrNull() }
            .toSet()
        return ControllerSettings(
            preferredMode = preferred,
            capabilityPreferences = preferences,
            disabledModes = disabled,
        )
    }

    private fun String.toControlMode(): ControlMode? =
        runCatching { ControlMode.valueOf(this) }.getOrNull()

    private companion object {
        const val PREFS = "izukijs_controller"
        const val KEY_PREFERRED = "preferred_mode"
        const val KEY_DISABLED = "disabled_modes"

        fun capabilityKey(capability: Capability) = "cap_${capability.name}"
    }
}
