package com.benton.izukijs.update

import android.content.Context
import com.benton.izukijs.R
import com.benton.izukijs.core.log.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    data class Available(val info: UpdateInfo) : UpdateState

    data class Downloading(val progress: Int) : UpdateState

    data class Downloaded(val file: File) : UpdateState

    data class CheckFailed(val message: String) : UpdateState

    data class DownloadFailed(val message: String) : UpdateState
}

class UpdateManager(
    private val context: Context,
    private val settingsRepository: UpdateSettingsRepository,
    private val logBus: LogBus? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var downloadJob: Job? = null

    private val checker = UpdateChecker(logBus)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _prompt = MutableStateFlow<UpdateInfo?>(null)
    val prompt: StateFlow<UpdateInfo?> = _prompt.asStateFlow()

    private var checkJob: Job? = null

    /** 下载失败后 state 不再是 Available，重试与打开 GitHub 仍需要上一次发现的版本信息。 */
    private var lastAvailableInfo: UpdateInfo? = null

    /** 安装失败时 state 会变成 DownloadFailed，重试应优先重新安装已下载的包。 */
    private var lastDownloadedFile: File? = null

    fun checkAutomatically() = check(manual = false)

    fun checkManually() = check(manual = true)

    private fun check(manual: Boolean) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        checkJob?.cancel()
        checkJob = scope.launch {
            _state.value = UpdateState.Checking
            when (val result = withContext(Dispatchers.IO) { checker.check() }) {
                is UpdateCheckResult.UpToDate -> {
                    _state.value = UpdateState.UpToDate
                    _prompt.value = null
                }

                is UpdateCheckResult.Failure -> _state.value = UpdateState.CheckFailed(result.message)

                is UpdateCheckResult.Available -> {
                    lastAvailableInfo = result.info
                    lastDownloadedFile = null
                    _state.value = UpdateState.Available(result.info)
                    if (!manual) _prompt.value = result.info
                }
            }
        }
    }

    fun dismissPrompt() {
        _prompt.value = null
    }

    fun downloadAndInstall() {
        val info = (_state.value as? UpdateState.Available)?.info ?: lastAvailableInfo ?: return
        if (info.apkUrl.isNullOrBlank()) {
            openOnGitHub()
            return
        }
        if (downloadJob?.isActive == true) return
        _prompt.value = null
        downloadJob = scope.launch {
            _state.value = UpdateState.Downloading(-1)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ApkInstaller.download(context, info) { percent ->
                        _state.value = UpdateState.Downloading(percent)
                    }
                }
            }
            result.fold(
                onSuccess = { file ->
                    lastDownloadedFile = file
                    val installed = file.exists() && file.length() > 0L &&
                        runCatching { ApkInstaller.install(context, file) }.getOrDefault(false)
                    _state.value = if (installed) UpdateState.Idle else UpdateState.Downloaded(file)
                },
                onFailure = {
                    logBus?.warn("更新下载失败: ${it.message}")
                    _state.value = UpdateState.DownloadFailed(
                        it.message ?: context.getString(R.string.update_download_failed_short),
                    )
                },
            )
        }
    }

    fun installDownloaded() {
        val file = (_state.value as? UpdateState.Downloaded)?.file ?: lastDownloadedFile ?: return
        if (!file.exists() || file.length() <= 0L) {
            _state.value = UpdateState.DownloadFailed(context.getString(R.string.update_error_apk_missing))
            return
        }
        if (runCatching { ApkInstaller.install(context, file) }.getOrDefault(false)) {
            _state.value = UpdateState.Idle
        } else {
            _state.value = UpdateState.DownloadFailed(context.getString(R.string.update_error_install_launch))
        }
    }

    /** 失败后的统一重试入口：已有安装包则重新安装，否则重新下载。 */
    fun retry() {
        val file = lastDownloadedFile
        if (file != null && file.exists() && file.length() > 0L) {
            installDownloaded()
        } else {
            downloadAndInstall()
        }
    }

    fun openOnGitHub() {
        val info = (_state.value as? UpdateState.Available)?.info ?: lastAvailableInfo
        _prompt.value = null
        ApkInstaller.openInBrowser(context, info?.releaseUrl?.ifBlank { UpdateChecker.RELEASES_PAGE } ?: UpdateChecker.RELEASES_PAGE)
    }

    fun startIfAutoCheckEnabled() {
        if (settingsRepository.current().autoCheck) checkAutomatically()
    }
}
