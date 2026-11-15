package com.benton.izukijs.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.benton.izukijs.core.log.LogBus
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    data class Available(val info: UpdateInfo) : UpdateState

    data class Downloading(val progress: Int) : UpdateState

    /** 已下载但缺少「安装未知应用」权限，需要用户去设置页授权。 */
    data object InstallPermissionRequired : UpdateState

    /** 已拉起系统安装器，等待用户完成安装。 */
    data object InstallPending : UpdateState

    data class CheckFailed(val failure: UpdateFailure) : UpdateState

    data class DownloadFailed(val failure: UpdateFailure) : UpdateState
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

    /** 最近一次发现的版本；下载 / 安装失败后仍保留，便于页面继续展示说明与重试。 */
    private val _availableInfo = MutableStateFlow<UpdateInfo?>(null)
    val availableInfo: StateFlow<UpdateInfo?> = _availableInfo.asStateFlow()

    private val _lastCheckedAt = MutableStateFlow<Long?>(null)
    val lastCheckedAt: StateFlow<Long?> = _lastCheckedAt.asStateFlow()

    private var checkJob: Job? = null

    /** 安装失败时 state 会变成 DownloadFailed，重试应优先重新安装已下载的包。 */
    private var lastDownloadedFile: File? = null

    fun checkAutomatically() = check(manual = false)

    fun checkManually() = check(manual = true)

    private fun check(manual: Boolean) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        checkJob?.cancel()
        checkJob = scope.launch {
            _state.value = UpdateState.Checking
            val result = withContext(Dispatchers.IO) { checker.check() }
            _lastCheckedAt.value = System.currentTimeMillis()
            when (result) {
                is UpdateCheckResult.UpToDate -> {
                    _state.value = UpdateState.UpToDate
                    _availableInfo.value = null
                    _prompt.value = null
                }

                is UpdateCheckResult.Failure -> _state.value = UpdateState.CheckFailed(result.failure)

                is UpdateCheckResult.Available -> {
                    lastDownloadedFile = null
                    _availableInfo.value = result.info
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
        val info = _availableInfo.value ?: return
        if (info.apkUrl.isNullOrBlank()) {
            openOnGitHub()
            return
        }
        if (downloadJob?.isActive == true) return
        _prompt.value = null
        downloadBlockReason(currentNetwork(), settingsRepository.current().wifiOnly)?.let { failure ->
            _state.value = UpdateState.DownloadFailed(failure)
            return
        }
        downloadJob = scope.launch {
            _state.value = UpdateState.Downloading(-1)
            val file = try {
                withContext(Dispatchers.IO) {
                    ApkInstaller.download(context, info) { percent ->
                        if (isActive) _state.value = UpdateState.Downloading(percent)
                    }
                }
            } catch (e: CancellationException) {
                ApkInstaller.cleanup(context)
                throw e
            } catch (t: Throwable) {
                logBus?.warn("更新下载失败: ${t.javaClass.simpleName}")
                _state.value = UpdateState.DownloadFailed(t.toUpdateFailure())
                return@launch
            }
            if (!isActive) {
                ApkInstaller.cleanup(context)
                return@launch
            }
            if (!file.exists() || file.length() <= 0L) {
                _state.value = UpdateState.DownloadFailed(UpdateFailure.MissingFile)
                return@launch
            }
            if (!matchesExpectedSize(file.length(), info.apkSizeBytes)) {
                ApkInstaller.cleanup(context)
                _state.value = UpdateState.DownloadFailed(UpdateFailure.SizeMismatch)
                return@launch
            }
            lastDownloadedFile = file
            beginInstall(file)
        }
    }

    /** 取消进行中的下载并清理半成品；已下载完成后的取消不会删除安装包。 */
    fun cancelDownload() {
        val job = downloadJob
        if (job == null || !job.isActive) return
        job.cancel()
        _state.value = UpdateState.Idle
    }

    fun installDownloaded() {
        val file = lastDownloadedFile ?: return
        beginInstall(file)
    }

    fun openInstallPermissionSettings() = ApkInstaller.openInstallPermissionSettings(context)

    /** 用户从系统设置授权返回后调用；仍缺权限时保持原状态，避免反复拉起设置页。 */
    fun retryInstallAfterPermission() {
        if (_state.value is UpdateState.InstallPermissionRequired &&
            ApkInstaller.canInstallPackages(context)
        ) {
            installDownloaded()
        }
    }

    /** 失败后的统一重试入口：检查失败就重查，下载失败就重下，安装失败就重装。 */
    fun retry() {
        when (val current = _state.value) {
            is UpdateState.CheckFailed -> checkManually()
            is UpdateState.DownloadFailed -> {
                val file = lastDownloadedFile
                val canReinstall = current.failure == UpdateFailure.InstallFailed &&
                    file != null && file.exists() && file.length() > 0L
                if (canReinstall) installDownloaded() else downloadAndInstall()
            }

            else -> Unit
        }
    }

    fun openOnGitHub() {
        val info = _availableInfo.value
        _prompt.value = null
        ApkInstaller.openInBrowser(
            context,
            info?.releaseUrl?.ifBlank { UpdateChecker.RELEASES_PAGE } ?: UpdateChecker.RELEASES_PAGE,
        )
    }

    fun startIfAutoCheckEnabled() {
        if (settingsRepository.current().autoCheck) checkAutomatically()
    }

    private fun beginInstall(file: File) {
        when (val result = ApkInstaller.install(context, file)) {
            InstallResult.Started -> _state.value = UpdateState.InstallPending
            InstallResult.NeedPermission -> _state.value = UpdateState.InstallPermissionRequired
            is InstallResult.Failed -> _state.value = UpdateState.DownloadFailed(result.failure)
        }
    }

    private fun currentNetwork(): UpdateNetwork {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return UpdateNetwork.NONE
        val network = manager.activeNetwork ?: return UpdateNetwork.NONE
        val capabilities = manager.getNetworkCapabilities(network) ?: return UpdateNetwork.NONE
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return UpdateNetwork.NONE
        }
        return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            UpdateNetwork.UNMETERED
        } else {
            UpdateNetwork.METERED
        }
    }

    private fun Throwable.toUpdateFailure(): UpdateFailure = when (this) {
        // 下载阶段没有读取错误正文，403 限流无法按正文识别，但 404 / 429 / 5xx 可复用同一分类。
        is UpdateHttpException -> UpdateChecker.classifyHttpFailure(code, null)

        is UnknownHostException, is ConnectException, is SocketTimeoutException ->
            UpdateFailure.Network

        else -> UpdateFailure.Unknown
    }
}
