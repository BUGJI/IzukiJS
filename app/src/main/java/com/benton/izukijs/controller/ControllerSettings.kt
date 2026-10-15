package com.benton.izukijs.controller

import android.content.Context
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 控制后端偏好。按「读取 / 操作」两档分别设置全局优先模式，并可为单项能力单独指定，
 * 例如「读控件树走无障碍、点击走 HID」的混合模式。
 */
data class ControllerSettings(
    val readMode: ControlMode? = null,
    val operateMode: ControlMode? = null,
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
            if (value.readMode == null) remove(KEY_READ) else putString(KEY_READ, value.readMode.name)
            if (value.operateMode == null) remove(KEY_OPERATE) else putString(KEY_OPERATE, value.operateMode.name)
            // 旧的单一全局优先模式已被读取 / 操作两档取代，保存时清理。
            remove(KEY_LEGACY_PREFERRED)
            Capability.entries.forEach { capability ->
                val key = capabilityKey(capability)
                val mode = value.capabilityPreferences[capability]
                if (mode == null) remove(key) else putString(key, mode.name)
            }
            putStringSet(KEY_DISABLED, value.disabledModes.map { it.name }.toSet())
        }.apply()
    }

    private fun load(): ControllerSettings {
        // 兼容旧版本：旧的单一 preferred_mode 同时用作读取 / 操作两档的初始值。
        val legacy = prefs.getString(KEY_LEGACY_PREFERRED, null)?.toControlMode()
        val read = prefs.getString(KEY_READ, null)?.toControlMode() ?: legacy
        val operate = prefs.getString(KEY_OPERATE, null)?.toControlMode() ?: legacy
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
            readMode = read,
            operateMode = operate,
            capabilityPreferences = preferences,
            disabledModes = disabled,
        )
    }

    private fun String.toControlMode(): ControlMode? =
        runCatching { ControlMode.valueOf(this) }.getOrNull()

    private companion object {
        const val PREFS = "izukijs_controller"
        const val KEY_LEGACY_PREFERRED = "preferred_mode"
        const val KEY_READ = "read_mode"
        const val KEY_OPERATE = "operate_mode"
        const val KEY_DISABLED = "disabled_modes"

        fun capabilityKey(capability: Capability) = "cap_${capability.name}"
    }
}
