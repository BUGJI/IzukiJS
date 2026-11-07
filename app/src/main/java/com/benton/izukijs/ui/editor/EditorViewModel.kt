package com.benton.izukijs.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.benton.izukijs.data.ScriptVersion
import com.benton.izukijs.di.AppContainer
import com.benton.izukijs.model.EnvField
import com.benton.izukijs.model.EnvText
import com.benton.izukijs.model.ScriptEnvSpec
import com.benton.izukijs.model.ScriptInfo
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 版本历史弹窗状态。 */
data class EditorHistoryState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val entries: List<Pair<ScriptVersion, String>> = emptyList(),
)

/** 运行前需要补全参数的提示。 */
data class EditorRunPrompt(
    val source: String,
    val fields: List<EnvField>,
    val initial: Map<String, String>,
)

/**
 * 编辑器的非 UI 业务逻辑：串行写盘、版本历史、运行参数解析与变量读取。
 *
 * 文本、选区、撤销栈与查找状态仍由 Compose 持有（它们与输入法 / 焦点强耦合）；
 * 这里只承载可复用、可测试的持久化与会话操作。
 */
class EditorViewModel(private val container: AppContainer) : ViewModel() {

    private val _history = MutableStateFlow(EditorHistoryState())
    val history: StateFlow<EditorHistoryState> = _history.asStateFlow()

    private val _runPrompt = MutableStateFlow<EditorRunPrompt?>(null)
    val runPrompt: StateFlow<EditorRunPrompt?> = _runPrompt.asStateFlow()

    // 串行化写盘并按脚本记录最新写入序号：避免自动保存与「返回 / 运行」保存并发写同一文件，
    // 以及旧内容覆盖新内容。按脚本分开计数，切换脚本时互不影响。
    private val writeMutex = Mutex()
    private val writeVersions = mutableMapOf<String, AtomicLong>()

    /**
     * 写盘并返回是否成功（被更新版本取代时视为成功，真正的落盘交给更新的那次写入完成）。
     */
    suspend fun persist(script: ScriptInfo, content: String): Boolean =
        withContext(NonCancellable) {
            val version = synchronized(writeVersions) {
                writeVersions.getOrPut(script.id) { AtomicLong(0L) }
            }.incrementAndGet()
            writeMutex.withLock {
                val latest = synchronized(writeVersions) {
                    writeVersions[script.id]?.get() ?: version
                }
                if (version != latest) return@withLock true
                runCatching { container.scriptRepository.writeAsync(script, content) }.isSuccess
            }
        }

    fun openHistory(scriptId: String) {
        _history.update { it.copy(open = true, loading = true) }
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) {
                container.scriptRepository.history.list(scriptId).map { version ->
                    val preview = container.scriptRepository.history.read(version)
                        .lineSequence()
                        .firstOrNull()
                        .orEmpty()
                        .take(80)
                    version to preview
                }
            }
            _history.update { it.copy(loading = false, entries = entries) }
        }
    }

    fun closeHistory() {
        _history.update { it.copy(open = false) }
    }

    suspend fun readVersion(version: ScriptVersion): String = withContext(Dispatchers.IO) {
        container.scriptRepository.history.read(version)
    }

    /** 恢复版本前强制留档当前内容，避免自动保存节流把当前版本吞掉。 */
    suspend fun recordCurrent(scriptId: String, content: String) = withContext(Dispatchers.IO) {
        container.scriptRepository.history.record(scriptId, content, force = true)
    }

    /**
     * 请求运行：无参数时直接启动；否则写入 [runPrompt] 由 UI 弹出参数对话框。
     */
    fun requestRun(script: ScriptInfo, runText: String) {
        viewModelScope.launch {
            val spec = ScriptEnvSpec.parse(runText)
            val stored = container.scriptEnvRepository.values(script.id)
            if (spec.isEmpty && stored.isEmpty()) {
                container.scriptExecutionManager.run(script.id, script.name, runText)
                _runPrompt.value = null
            } else {
                _runPrompt.value = EditorRunPrompt(
                    source = runText,
                    fields = spec.fields,
                    initial = EnvText.resolve(
                        defaults = spec.defaults(),
                        global = container.globalEnvRepository.current(),
                        script = container.scriptEnvRepository.env(script.id),
                        runtime = stored,
                        useGlobal = container.scriptEnvRepository.useGlobalEnv(script.id),
                    ),
                )
            }
        }
    }

    fun confirmRun(script: ScriptInfo, values: Map<String, String>) {
        val prompt = _runPrompt.value ?: return
        container.scriptEnvRepository.saveValues(script.id, values)
        container.scriptExecutionManager.run(script.id, script.name, prompt.source, values)
        _runPrompt.value = null
    }

    fun dismissRun() {
        _runPrompt.value = null
    }

    fun variables(scriptId: String): Pair<Map<String, String>, Map<String, String>> =
        container.scriptEnvRepository.values(scriptId) to
            container.scriptEnvRepository.state(scriptId)
}
