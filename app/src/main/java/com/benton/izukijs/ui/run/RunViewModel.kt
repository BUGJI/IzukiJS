package com.benton.izukijs.ui.run

import androidx.lifecycle.viewModelScope
import com.benton.izukijs.di.AppContainer
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.model.RecentRun
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.ui.common.ScriptLaunchViewModel
import com.benton.izukijs.virtualscreen.VirtualScreenSettings
import com.benton.izukijs.virtualscreen.VirtualScreenState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 运行页的完整 UI 状态。 */
data class RunUiState(
    val readyModes: Set<ControlMode> = emptySet(),
    val screenCaptureActive: Boolean = false,
    val running: Boolean = false,
    val runningScript: String? = null,
    val runningScriptId: String? = null,
    val queuedScript: String? = null,
    val queuedTerminating: Boolean = false,
    val runState: Map<String, String> = emptyMap(),
    val virtualScreenState: VirtualScreenState = VirtualScreenState.Idle,
    val virtualScreenSettings: VirtualScreenSettings = VirtualScreenSettings(),
    val previewAttached: Boolean = false,
    val scripts: List<ScriptInfo> = emptyList(),
    val scriptsLoaded: Boolean = false,
    val recentItems: List<RunItem> = emptyList(),
)

/** 快速启动项：仍然存在的脚本 + 最近一次运行时间。 */
data class RunItem(val script: ScriptInfo, val at: Long)

/**
 * 运行页状态持有者：聚合各仓库 / 管理器的状态，并把「读取脚本、决定是否弹参数框、
 * 记录运行历史」等逻辑收在这里，Composable 只负责渲染与交互。
 *
 * 手动运行入口由 [ScriptLaunchViewModel] 统一提供，脚本中心复用同一套逻辑。
 */
class RunViewModel(container: AppContainer) : ScriptLaunchViewModel(container) {

    private val scriptsFlow = MutableStateFlow<List<ScriptInfo>>(emptyList())

    private val _uiState = MutableStateFlow(RunUiState())
    val uiState: StateFlow<RunUiState> = _uiState.asStateFlow()

    init {
        container.controllerManager.effectiveReadyModes.collectInto { state, value ->
            state.copy(readyModes = value)
        }
        container.screenCapture.active.collectInto { state, value ->
            state.copy(screenCaptureActive = value)
        }
        execution.running.collectInto { state, value ->
            state.copy(running = value)
        }
        execution.runningScript.collectInto { state, value ->
            state.copy(runningScript = value)
        }
        execution.runningScriptId.collectInto { state, value ->
            state.copy(runningScriptId = value)
        }
        execution.queuedScript.collectInto { state, value ->
            state.copy(queuedScript = value)
        }
        execution.queuedTerminating.collectInto { state, value ->
            state.copy(queuedTerminating = value)
        }
        container.virtualScreenManager.state.collectInto { state, value ->
            state.copy(virtualScreenState = value)
        }
        container.virtualScreenSettingsRepository.settings.collectInto { state, value ->
            state.copy(virtualScreenSettings = value)
        }
        container.virtualScreenManager.previewAttached.collectInto { state, value ->
            state.copy(previewAttached = value)
        }

        viewModelScope.launch {
            combine(execution.runningScriptId, scriptEnvRepository.data) { id, data ->
                id?.let { data[it]?.state }.orEmpty()
            }.collect { runState ->
                _uiState.update { it.copy(runState = runState) }
            }
        }

        viewModelScope.launch {
            combine(container.runHistoryRepository.recent, scriptsFlow) { recent, scripts ->
                buildRecentItems(recent, scripts)
            }.collect { items ->
                _uiState.update { it.copy(recentItems = items) }
            }
        }

        // 脚本增删改（含编辑器保存）后仓库会自增 revision；据此重新拉取，
        // 保证快捷区与运行历史同步。
        viewModelScope.launch {
            scriptRepository.revision.collectLatest {
                val scripts = scriptRepository.listAsync()
                scriptsFlow.value = scripts
                _uiState.update { it.copy(scripts = scripts, scriptsLoaded = true) }
            }
        }
    }

    fun stop() {
        execution.requestStop()
    }

    fun cancelQueued() {
        execution.cancelQueued()
    }

    fun clearHistory() {
        container.runHistoryRepository.clear()
    }

    private fun <T> Flow<T>.collectInto(update: (RunUiState, T) -> RunUiState) {
        viewModelScope.launch {
            collect { value -> _uiState.update { state -> update(state, value) } }
        }
    }

    private fun buildRecentItems(
        recent: List<RecentRun>,
        scripts: List<ScriptInfo>,
    ): List<RunItem> {
        val byId = scripts.associateBy { it.id }
        val byName = scripts.associateBy { it.name }
        return recent.mapNotNull { record ->
            val script = record.id.takeIf { it.isNotBlank() }?.let(byId::get)
                ?: if (record.id.isBlank()) byName[record.name] else null
            script?.let { RunItem(it, record.at) }
        }
    }
}
