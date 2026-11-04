package com.benton.izukijs.ui.run

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.benton.izukijs.di.AppContainer
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.model.EnvField
import com.benton.izukijs.model.EnvText
import com.benton.izukijs.model.RunRecord
import com.benton.izukijs.model.ScriptEnvSpec
import com.benton.izukijs.model.ScriptInfo
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

/** 需要用户填写运行参数的脚本。 */
data class EnvDialogState(
    val script: ScriptInfo,
    val source: String,
    val fields: List<EnvField>,
    val initial: Map<String, String>,
)

/**
 * 运行页状态持有者：聚合各仓库 / 管理器的状态，并把「读取脚本、决定是否弹参数框、
 * 记录运行历史」等逻辑收在这里，Composable 只负责渲染与交互。
 */
class RunViewModel(private val container: AppContainer) : ViewModel() {

    private val execution = container.scriptExecutionManager
    private val scriptRepository = container.scriptRepository
    private val scriptEnvRepository = container.scriptEnvRepository

    private val scriptsFlow = MutableStateFlow<List<ScriptInfo>>(emptyList())

    private val _uiState = MutableStateFlow(RunUiState())
    val uiState: StateFlow<RunUiState> = _uiState.asStateFlow()

    private val _envDialog = MutableStateFlow<EnvDialogState?>(null)
    val envDialog: StateFlow<EnvDialogState?> = _envDialog.asStateFlow()

    init {
        container.controllerManager.readyModes.collectInto { state, value ->
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

    fun requestRun(script: ScriptInfo) {
        viewModelScope.launch {
            val source = scriptRepository.readAsync(script)
            val spec = ScriptEnvSpec.parse(source)
            val stored = scriptEnvRepository.values(script.id)
            if (spec.isEmpty && stored.isEmpty()) {
                execution.run(script.id, script.name, source)
            } else {
                _envDialog.value = EnvDialogState(
                    script = script,
                    source = source,
                    fields = spec.fields,
                    initial = EnvText.resolve(
                        defaults = spec.defaults(),
                        global = container.globalEnvRepository.current(),
                        script = scriptEnvRepository.env(script.id),
                        runtime = stored,
                        useGlobal = scriptEnvRepository.useGlobalEnv(script.id),
                    ),
                )
            }
        }
    }

    fun confirmEnv(values: Map<String, String>) {
        val state = _envDialog.value ?: return
        scriptEnvRepository.saveValues(state.script.id, values)
        execution.run(state.script.id, state.script.name, state.source, values)
        _envDialog.value = null
    }

    fun dismissEnv() {
        _envDialog.value = null
    }

    fun stop() {
        execution.requestStop()
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
        recent: List<RunRecord>,
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
