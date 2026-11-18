package com.benton.izukijs.ui.scripts

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.benton.izukijs.data.ScriptImporter
import com.benton.izukijs.data.ScriptNameResolver
import com.benton.izukijs.di.AppContainer
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.subscription.ScriptSubscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 脚本列表与订阅摘要。 */
data class ScriptsUiState(
    val scripts: List<ScriptInfo> = emptyList(),
    val subscriptions: Map<String, ScriptSubscription> = emptyMap(),
    val loaded: Boolean = false,
    val loadError: Boolean = false,
)

/**
 * 脚本列表的读取与文件导入 / 导出。
 *
 * 仓库读取与 SAF 流操作都放到 IO；页面只负责启动系统选择器、展示 Snackbar 与导航。
 */
class ScriptsViewModel(private val container: AppContainer) : ViewModel() {

    private val appContext = container.application

    private val _state = MutableStateFlow(ScriptsUiState())
    val state: StateFlow<ScriptsUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    fun refresh() {
        // 仓库 revision 连续变化时只保留最后一次刷新，避免旧结果覆盖新列表。
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.value = _state.value.copy(loadError = false)
            try {
                val (scripts, subscriptions) = withContext(Dispatchers.IO) {
                    container.scriptRepository.listAsync() to
                        container.subscriptionRepository.listAsync()
                }
                _state.value = ScriptsUiState(
                    scripts = scripts,
                    subscriptions = subscriptions.associateBy { it.scriptId },
                    loaded = true,
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.value = _state.value.copy(loaded = true, loadError = true)
            }
        }
    }

    suspend fun exportText(script: ScriptInfo, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val text = runCatching { container.scriptRepository.read(script) }.getOrNull()
            ?: return@withContext false
        writeTextToUri(appContext, uri, text)
    }

    suspend fun exportZip(ids: List<String>, uri: Uri): Int = withContext(Dispatchers.IO) {
        val entries = mutableListOf<Pair<String, String>>()
        for (id in ids) {
            val script = container.scriptRepository.findAsync(id) ?: continue
            val content = runCatching { container.scriptRepository.read(script) }.getOrNull()
                ?: continue
            entries += (script.name + ".js") to content
        }
        if (entries.isEmpty() || !writeZipToUri(appContext, uri, entries)) 0 else entries.size
    }

    suspend fun importText(uri: Uri): ScriptInfo? = withContext(Dispatchers.IO) {
        val text = readTextFromUri(appContext, uri) ?: return@withContext null
        val displayName = ScriptNameResolver.queryDisplayName(appContext.contentResolver, uri)
        val name = ScriptNameResolver.fromDocument(displayName, uri.lastPathSegment)
        runCatching { container.scriptRepository.create(name, text) }.getOrNull()
    }

    suspend fun importScan(uri: Uri): ScriptImporter.Result = withContext(Dispatchers.IO) {
        container.scriptImporter.importFromImage(uri)
    }
}
