package com.benton.izukijs.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.benton.izukijs.ai.AiConfig
import com.benton.izukijs.controller.ControllerSettings
import com.benton.izukijs.data.AppearanceSettings
import com.benton.izukijs.data.EditorSettings
import com.benton.izukijs.data.UnlockSettings
import com.benton.izukijs.di.AppContainer
import com.benton.izukijs.i18n.AppLanguage
import com.benton.izukijs.mcp.McpStatus
import com.benton.izukijs.mcp.core.McpConfig
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.ocr.OcrConfig
import com.benton.izukijs.runtime.LogSettings
import com.benton.izukijs.runtime.RuntimeSettings
import com.benton.izukijs.service.CaptureSettings
import com.benton.izukijs.update.UpdateSettings
import com.benton.izukijs.update.UpdateState
import com.benton.izukijs.virtualscreen.VirtualScreenSettings
import com.benton.izukijs.virtualscreen.VirtualScreenState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置页的聚合状态。 */
data class SettingsUiState(
    val controllerSettings: ControllerSettings = ControllerSettings(),
    val readyModes: Set<ControlMode> = emptySet(),
    val ocrConfig: OcrConfig = OcrConfig(),
    val captureSettings: CaptureSettings = CaptureSettings(),
    val logSettings: LogSettings = LogSettings(),
    val aiConfig: AiConfig = AiConfig(),
    val mcpStatus: McpStatus = McpStatus(),
    val mcpConfig: McpConfig = McpConfig(),
    val language: AppLanguage = AppLanguage.SYSTEM,
    val appearance: AppearanceSettings = AppearanceSettings(),
    val updateSettings: UpdateSettings = UpdateSettings(),
    val updateState: UpdateState = UpdateState.Idle,
    val globalEnv: Map<String, String> = emptyMap(),
    val virtualScreenSettings: VirtualScreenSettings = VirtualScreenSettings(),
    val virtualScreenState: VirtualScreenState = VirtualScreenState.Idle,
    val unlockSettings: UnlockSettings = UnlockSettings(),
    val runtimeSettings: RuntimeSettings = RuntimeSettings(),
    val editorSettings: EditorSettings = EditorSettings(),
)

/**
 * 设置页的状态持有者：聚合各配置仓库与控制器状态，并集中保存动作。
 *
 * 设置标签与所有二级页共享 Activity 作用域的同一实例，避免每个页面各订阅一遍配置。
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        container.controllerSettingsRepository.settings.collectInto { state, value ->
            state.copy(controllerSettings = value)
        }
        container.controllerManager.readyModes.collectInto { state, value ->
            state.copy(readyModes = value)
        }
        container.ocrConfigRepository.config.collectInto { state, value ->
            state.copy(ocrConfig = value)
        }
        container.captureSettingsRepository.settings.collectInto { state, value ->
            state.copy(captureSettings = value)
        }
        container.logSettingsRepository.settings.collectInto { state, value ->
            state.copy(logSettings = value)
        }
        container.aiConfigRepository.config.collectInto { state, value ->
            state.copy(aiConfig = value)
        }
        container.mcpServerController.status.collectInto { state, value ->
            state.copy(mcpStatus = value)
        }
        container.mcpConfigRepository.config.collectInto { state, value ->
            state.copy(mcpConfig = value)
        }
        container.languageRepository.language.collectInto { state, value ->
            state.copy(language = value)
        }
        container.appearanceRepository.settings.collectInto { state, value ->
            state.copy(appearance = value)
        }
        container.updateSettingsRepository.settings.collectInto { state, value ->
            state.copy(updateSettings = value)
        }
        container.updateManager.state.collectInto { state, value ->
            state.copy(updateState = value)
        }
        container.globalEnvRepository.values.collectInto { state, value ->
            state.copy(globalEnv = value)
        }
        container.virtualScreenSettingsRepository.settings.collectInto { state, value ->
            state.copy(virtualScreenSettings = value)
        }
        container.virtualScreenManager.state.collectInto { state, value ->
            state.copy(virtualScreenState = value)
        }
        container.unlockSettingsRepository.settings.collectInto { state, value ->
            state.copy(unlockSettings = value)
        }
        container.runtimeSettingsRepository.settings.collectInto { state, value ->
            state.copy(runtimeSettings = value)
        }
        container.editorSettingsRepository.settings.collectInto { state, value ->
            state.copy(editorSettings = value)
        }
    }

    fun saveAppearance(value: AppearanceSettings) = container.appearanceRepository.save(value)

    fun saveController(value: ControllerSettings) = container.controllerSettingsRepository.save(value)

    fun saveOcr(value: OcrConfig) = container.ocrConfigRepository.save(value)

    fun saveCapture(value: CaptureSettings) = container.captureSettingsRepository.save(value)

    fun saveVirtualScreen(value: VirtualScreenSettings) =
        container.virtualScreenSettingsRepository.save(value)

    fun saveUnlock(value: UnlockSettings) = container.unlockSettingsRepository.save(value)

    fun setUnlockPin(pin: String) = container.unlockSettingsRepository.setPin(pin)

    fun clearUnlockPattern() = container.unlockSettingsRepository.setPattern(null)

    fun saveRuntimeSettings(value: RuntimeSettings) = container.runtimeSettingsRepository.save(value)

    fun saveLanguage(value: AppLanguage) = container.languageRepository.save(value)

    fun saveEditorSettings(value: EditorSettings) = container.editorSettingsRepository.save(value)

    fun saveLogSettings(value: LogSettings) = container.logSettingsRepository.save(value)

    fun saveUpdateSettings(value: UpdateSettings) = container.updateSettingsRepository.save(value)

    fun checkForUpdates() = container.updateManager.checkManually()

    fun downloadAndInstallUpdate() = container.updateManager.downloadAndInstall()

    fun openUpdateOnGitHub() = container.updateManager.openOnGitHub()

    fun installDownloadedUpdate() = container.updateManager.installDownloaded()

    suspend fun logFileSizeBytes(): Long =
        withContext(Dispatchers.IO) { container.logFileStore.sizeBytes() }

    suspend fun exportLogs(file: File): Boolean =
        withContext(Dispatchers.IO) { container.logFileStore.exportTo(file) }

    suspend fun trimLogs() = withContext(Dispatchers.IO) { container.logFileStore.trim() }

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        container.logBus.clear()
        container.logFileStore.clear()
    }

    suspend fun exportConfig(file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            file.writeText(container.configBackupManager.exportJson())
            true
        }.getOrDefault(false)
    }

    fun importConfig(json: String) = container.configBackupManager.importJson(json)

    fun resetConfig() = container.configBackupManager.resetAll()

    fun capabilitiesByMode(): Map<ControlMode, Set<Capability>> =
        container.controllerManager.capabilitiesByMode()

    fun controllerMode(capability: Capability): ControlMode? =
        container.controllerManager.controllerFor(capability)?.mode

    fun virtualScreenAvailable(): Boolean = container.virtualScreenManager.available()

    private fun <T> Flow<T>.collectInto(update: (SettingsUiState, T) -> SettingsUiState) {
        viewModelScope.launch {
            collect { value -> _uiState.update { state -> update(state, value) } }
        }
    }
}
