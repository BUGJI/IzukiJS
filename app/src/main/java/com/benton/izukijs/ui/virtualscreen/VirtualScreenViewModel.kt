package com.benton.izukijs.ui.virtualscreen

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.benton.izukijs.R
import com.benton.izukijs.di.AppContainer
import com.benton.izukijs.virtualscreen.VirtualScreenOptions
import com.benton.izukijs.virtualscreen.VirtualScreenResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 应用选择列表的加载状态。 */
data class VirtualScreenAppsState(
    val apps: List<VirtualScreenApp> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val allowed: Boolean = true,
)

/** 虚拟屏页面里截图、OCR 与诊断轮询的状态。 */
data class VirtualScreenToolsState(
    val frameSeq: Long = 0L,
    val lastStopReason: String? = null,
    val lastStopAt: Long = 0L,
    val screenshotLoading: Boolean = false,
    val ocrLoading: Boolean = false,
    val ocrText: String? = null,
    val launchingPackage: String? = null,
    val keyboardLoading: Boolean = false,
)

sealed interface VirtualScreenEvent {
    data class Message(val text: String) : VirtualScreenEvent
    data class ScreenshotSaved(val uri: Uri) : VirtualScreenEvent
    data object TextSent : VirtualScreenEvent
}

/**
 * 虚拟屏控制台的状态与动作。
 *
 * 应用列表、截图、OCR 与诊断轮询都放在这里，页面只负责渲染；
 * 所有阻塞操作离开主线程，避免预览和触摸被长任务拖慢。
 */
class VirtualScreenViewModel(private val container: AppContainer) : ViewModel() {

    private val manager = container.virtualScreenManager
    private val appContext = container.application

    private val _apps = MutableStateFlow(VirtualScreenAppsState())
    val apps: StateFlow<VirtualScreenAppsState> = _apps.asStateFlow()

    private val _tools = MutableStateFlow(
        VirtualScreenToolsState(
            frameSeq = manager.frameSeq(),
            lastStopReason = manager.lastStopReason(),
            lastStopAt = manager.lastStopAt(),
        ),
    )
    val tools: StateFlow<VirtualScreenToolsState> = _tools.asStateFlow()

    private val _events = MutableSharedFlow<VirtualScreenEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<VirtualScreenEvent> = _events.asSharedFlow()

    private var diagnosticsJob: Job? = null

    /** 读取可启动应用；[force] 用于应用列表权限重新打开后刷新。 */
    fun loadApps(force: Boolean = false) {
        val allowed = container.runtimeSettingsRepository.current().appList
        if (!allowed) {
            _apps.value = VirtualScreenAppsState(allowed = false, loaded = true)
            return
        }
        val current = _apps.value
        if (current.loading || (current.loaded && current.allowed && !force)) return
        _apps.value = current.copy(allowed = true, loading = true)
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { queryLaunchableApps() }
            _apps.value = VirtualScreenAppsState(
                apps = loaded,
                loading = false,
                loaded = true,
                allowed = true,
            )
        }
    }

    /** 页面打开期间轮询帧序号与上次停止原因，开销只有一次本地读取。 */
    fun startDiagnostics() {
        if (diagnosticsJob?.isActive == true) return
        diagnosticsJob = viewModelScope.launch {
            while (isActive) {
                _tools.update {
                    it.copy(
                        frameSeq = manager.frameSeq(),
                        lastStopReason = manager.lastStopReason(),
                        lastStopAt = manager.lastStopAt(),
                    )
                }
                delay(DIAGNOSTICS_INTERVAL_MS)
            }
        }
    }

    fun stopDiagnostics() {
        diagnosticsJob?.cancel()
        diagnosticsJob = null
    }

    fun start() {
        if (scriptActive()) return
        viewModelScope.launch { manager.start() }
    }

    fun stop() {
        if (scriptActive()) return
        viewModelScope.launch { manager.stop() }
    }

    fun stopScript() {
        container.scriptExecutionManager.requestStop()
    }

    fun applyResolution(resolution: VirtualScreenResolution) {
        if (scriptActive()) return
        val normalized = resolution.normalized()
        if (manager.isActive()) {
            viewModelScope.launch { manager.start(VirtualScreenOptions(normalized)) }
        } else {
            container.virtualScreenSettingsRepository.saveResolution(normalized)
        }
    }

    fun sendKey(keyCode: Int) {
        if (scriptActive()) return
        viewModelScope.launch {
            val ok = manager.key(keyCode)
            if (!ok) {
                _events.emit(
                    VirtualScreenEvent.Message(
                        appContext.getString(R.string.virtual_screen_input_failed),
                    ),
                )
            }
        }
    }

    /** 向虚拟屏发送文本；成功后发出 [VirtualScreenEvent.TextSent] 供页面关闭弹窗。 */
    fun sendText(text: String) {
        if (scriptActive()) return
        if (text.isBlank()) return
        if (_tools.value.keyboardLoading) return
        viewModelScope.launch {
            _tools.update { it.copy(keyboardLoading = true) }
            val ok = try {
                manager.inputText(text)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                false
            } finally {
                _tools.update { it.copy(keyboardLoading = false) }
            }
            if (ok) {
                _events.emit(VirtualScreenEvent.TextSent)
            } else {
                _events.emit(
                    VirtualScreenEvent.Message(
                        appContext.getString(R.string.virtual_screen_input_failed),
                    ),
                )
            }
        }
    }

    fun launchApp(packageName: String) {
        if (scriptActive()) return
        if (_tools.value.launchingPackage != null) return
        viewModelScope.launch {
            _tools.update { it.copy(launchingPackage = packageName) }
            val ok = manager.launch(packageName)
            _tools.update { it.copy(launchingPackage = null) }
            if (!ok) {
                _events.emit(
                    VirtualScreenEvent.Message(
                        appContext.getString(
                            R.string.virtual_screen_apps_launch_failed,
                            packageName,
                        ),
                    ),
                )
            }
        }
    }

    fun saveScreenshot() {
        if (_tools.value.screenshotLoading) return
        viewModelScope.launch {
            _tools.update { it.copy(screenshotLoading = true) }
            try {
                val bitmap = withContext(Dispatchers.IO) { manager.capture(0L) }
                if (bitmap == null) {
                    _events.emit(
                        VirtualScreenEvent.Message(
                            appContext.getString(R.string.virtual_screen_capture_failed),
                        ),
                    )
                    return@launch
                }
                val uri = withContext(Dispatchers.IO) {
                    saveVirtualScreenFrame(appContext, bitmap)
                }
                if (uri != null) {
                    _events.emit(VirtualScreenEvent.ScreenshotSaved(uri))
                } else {
                    _events.emit(
                        VirtualScreenEvent.Message(
                            appContext.getString(R.string.virtual_screen_screenshot_failed),
                        ),
                    )
                }
            } finally {
                _tools.update { it.copy(screenshotLoading = false) }
            }
        }
    }

    fun recognizeText() {
        if (_tools.value.ocrLoading) return
        viewModelScope.launch {
            _tools.update { it.copy(ocrLoading = true) }
            try {
                val bitmap = withContext(Dispatchers.IO) { manager.capture(0L) }
                if (bitmap == null) {
                    _events.emit(
                        VirtualScreenEvent.Message(
                            appContext.getString(R.string.virtual_screen_capture_failed),
                        ),
                    )
                    return@launch
                }
                val result = try {
                    withContext(Dispatchers.IO) { container.ocrProcessor.recognize(bitmap) }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    null
                } finally {
                    bitmap.recycle()
                }
                if (result == null) {
                    _events.emit(
                        VirtualScreenEvent.Message(
                            appContext.getString(R.string.virtual_screen_ocr_failed),
                        ),
                    )
                } else if (result.text.isBlank()) {
                    _events.emit(
                        VirtualScreenEvent.Message(
                            appContext.getString(R.string.virtual_screen_ocr_empty),
                        ),
                    )
                } else {
                    _tools.update { it.copy(ocrText = result.text) }
                }
            } finally {
                _tools.update { it.copy(ocrLoading = false) }
            }
        }
    }

    fun clearOcr() {
        _tools.update { it.copy(ocrText = null) }
    }

    override fun onCleared() {
        stopDiagnostics()
    }

    private fun scriptActive(): Boolean =
        container.scriptExecutionManager.running.value

    private fun queryLaunchableApps(): List<VirtualScreenApp> {
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val ownPackage = appContext.packageName
        val resolved = runCatching {
            @Suppress("QueryPermissionsNeeded")
            pm.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList())
        return resolved.asSequence()
            .mapNotNull { info ->
                val activityInfo = info.activityInfo ?: return@mapNotNull null
                val packageName = activityInfo.packageName ?: return@mapNotNull null
                if (packageName == ownPackage) return@mapNotNull null
                VirtualScreenApp(
                    packageName = packageName,
                    label = info.loadLabel(pm).toString().ifBlank { packageName },
                    isSystem = activityInfo.applicationInfo?.let {
                        it.flags and ApplicationInfo.FLAG_SYSTEM != 0
                    } ?: false,
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(
                compareBy<VirtualScreenApp, String>(String.CASE_INSENSITIVE_ORDER) { it.label },
            )
            .toList()
    }

    private companion object {
        const val DIAGNOSTICS_INTERVAL_MS = 1_000L
    }
}
