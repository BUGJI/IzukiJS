package com.benton.izukijs.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.benton.izukijs.data.ScriptRepository
import com.benton.izukijs.data.ScriptStaleException
import com.benton.izukijs.data.ScriptVersion
import com.benton.izukijs.di.AppContainer
import com.benton.izukijs.model.EnvField
import com.benton.izukijs.model.EnvText
import com.benton.izukijs.model.ScriptEnvSpec
import com.benton.izukijs.model.ScriptInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

/** 磁盘内容与编辑器保存基准不一致，等待用户选择处理方式。 */
data class EditorConflict(
    val scriptId: String,
    val actualSha256: String,
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

    // 编辑器保存基准由 ViewModel 持有：只有真正落盘成功的内容才推进它。
    // UI 只观察该值计算脏标记，避免多个并发保存的完成顺序把 savedCode 回退。
    private val _savedContent = MutableStateFlow<String?>(null)
    val savedContent: StateFlow<String?> = _savedContent.asStateFlow()

    private val _conflict = MutableStateFlow<EditorConflict?>(null)
    val conflict: StateFlow<EditorConflict?> = _conflict.asStateFlow()

    private var boundScriptId: String? = null
    private val persistQueue = EditorPersistQueue()

    /**
     * 绑定当前编辑的脚本与磁盘内容。配置变更时 ViewModel 仍存活，重复绑定同一脚本不应
     * 用可能滞后的 UI 镜像覆盖已经落盘的新内容。
     */
    fun bindDocument(scriptId: String, savedContent: String) {
        synchronized(this) {
            if (boundScriptId == scriptId && _savedContent.value != null) return
            boundScriptId = scriptId
            _savedContent.value = savedContent
            _conflict.value = null
        }
    }

    /** 冲突解决后把保存基准切到磁盘 / 新写入内容。 */
    fun resetSavedContent(scriptId: String, content: String) {
        synchronized(this) {
            boundScriptId = scriptId
            _savedContent.value = content
            if (_conflict.value?.scriptId == scriptId) _conflict.value = null
        }
    }

    fun clearConflict() {
        _conflict.value = null
    }

    /**
     * 写盘并返回最终结果。
     *
     * [force] 为 true 时跳过外部修改校验，用于冲突弹窗里的「覆盖保存」。
     * 被更新版本取代的调用会等待最终写入，返回/运行不会早于真正落盘。
     */
    suspend fun persist(
        script: ScriptInfo,
        content: String,
        force: Boolean = false,
    ): PersistOutcome = withContext(NonCancellable) {
        persistQueue.run(script.id) {
            val expectedSha256 = if (force) {
                null
            } else {
                _savedContent.value?.let { ScriptRepository.sha256(it) }
            }
            val outcome = try {
                container.scriptRepository.writeAsync(
                    script = script,
                    content = content,
                    expectedSha256 = expectedSha256,
                )
                PersistOutcome.Saved(content)
            } catch (e: ScriptStaleException) {
                PersistOutcome.Conflict(e.actualSha256)
            } catch (t: Throwable) {
                PersistOutcome.Failed(t.message)
            }
            when (outcome) {
                is PersistOutcome.Saved -> {
                    _savedContent.value = outcome.content
                    if (_conflict.value?.scriptId == script.id) _conflict.value = null
                }

                is PersistOutcome.Conflict -> {
                    _conflict.value = EditorConflict(script.id, outcome.actualSha256)
                }

                is PersistOutcome.Failed -> Unit
            }
            outcome
        }
    }

    fun openHistory(scriptId: String) {
        _history.update { it.copy(open = true, loading = true) }
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) {
                container.scriptRepository.history.list(scriptId).map { version ->
                    val preview = container.scriptRepository.history.readOrNull(version)
                        ?.lineSequence()
                        ?.firstOrNull()
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

    suspend fun readVersion(version: ScriptVersion): String? = withContext(Dispatchers.IO) {
        container.scriptRepository.history.readOrNull(version)
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
}
