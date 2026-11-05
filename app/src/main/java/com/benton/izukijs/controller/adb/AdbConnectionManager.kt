package com.benton.izukijs.controller.adb

import android.content.Context
import android.os.Build
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.permission.LocalNetworkPermission
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit

/**
 * 无线调试（ADB over Wi-Fi）连接管理。
 *
 * 与 Shizuku 一样使用 shell UID 执行命令，但连接来自 App 自己的 adb 客户端，
 * 不依赖 Shizuku 服务进程。配对信息保存在 [AdbKeyStore]。
 */
class AdbConnectionManager(
    private val context: Context,
    private val keyStore: AdbKeyStore,
    private val logBus: LogBus,
) : AbsAdbConnectionManager() {

    sealed interface State {
        data object Unsupported : State
        data object NeedsLocalNetworkPermission : State
        data object NeedsPairing : State
        data object Disconnected : State
        data object Pairing : State
        data object Connecting : State
        data object Connected : State
        data class Error(val message: String) : State
    }

    private val mutex = Mutex()

    private val _state = MutableStateFlow<State>(initialState())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun hasLocalNetworkPermission(): Boolean = LocalNetworkPermission.isGranted(context)

    fun hasIdentity(): Boolean = keyStore.hasIdentity()

    fun isReady(): Boolean = isSupported() && isConnected()

    override fun getPrivateKey(): PrivateKey = keyStore.loadOrCreate().privateKey

    override fun getCertificate(): Certificate = keyStore.loadOrCreate().certificate

    override fun getDeviceName(): String = "izuki-${Build.MODEL ?: "android"}"

    /** mDNS 自动发现连接端口并连接。 */
    suspend fun connect(timeoutMs: Long = DEFAULT_DISCOVERY_TIMEOUT_MS): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock { connectLocked(timeoutMs) }
        }

    /** 应用启动时的静默重连：失败只更新状态，不写警告日志。 */
    suspend fun connectQuietly(timeoutMs: Long = DEFAULT_DISCOVERY_TIMEOUT_MS): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock { connectLocked(timeoutMs, quiet = true) }
        }

    /** 手动指定主机 / 端口连接，用于 mDNS 被网络隔离时的回退。 */
    suspend fun connectManual(host: String, port: Int, timeoutMs: Long = CONNECT_TIMEOUT_MS): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!isSupported()) {
                    _state.value = State.Unsupported
                    return@withLock false
                }
                if (!hasLocalNetworkPermission()) {
                    _state.value = State.NeedsLocalNetworkPermission
                    return@withLock false
                }
                _state.value = State.Connecting
                runCatching {
                    setTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    setHostAddress(host)
                    connect(host, port)
                }.fold(
                    onSuccess = { connected ->
                        if (connected && isConnected()) {
                            _state.value = State.Connected
                            logBus.success("无线调试已连接（$host:$port）")
                            true
                        } else {
                            _state.value = State.Disconnected
                            logBus.warn("无线调试连接失败（$host:$port）")
                            false
                        }
                    },
                    onFailure = { throwable ->
                        handleConnectFailure(throwable)
                        false
                    },
                )
            }
        }

    /**
     * 与系统配对对话框配合使用：先 pair，再尝试连接。
     * host 应是本机地址，port 是系统配对对话框显示的端口。
     */
    suspend fun pairAndConnect(host: String, port: Int, pairingCode: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!isSupported()) {
                    _state.value = State.Unsupported
                    return@withLock false
                }
                if (!hasLocalNetworkPermission()) {
                    _state.value = State.NeedsLocalNetworkPermission
                    return@withLock false
                }
                _state.value = State.Pairing
                runCatching {
                    pair(host, port, pairingCode.trim())
                }.fold(
                    onSuccess = {
                        logBus.success("无线调试配对成功")
                        _state.value = State.Disconnected
                        connectLocked(DEFAULT_DISCOVERY_TIMEOUT_MS)
                    },
                    onFailure = { throwable ->
                        logBus.warn("无线调试配对失败: ${throwable.message}")
                        _state.value = State.Error(throwable.message ?: "配对失败")
                        false
                    },
                )
            }
        }

    suspend fun ensureConnected(timeoutMs: Long = DEFAULT_DISCOVERY_TIMEOUT_MS): Boolean =
        if (isConnected()) true else connect(timeoutMs)

    suspend fun disconnectConnection() = withContext(Dispatchers.IO) {
        mutex.withLock { disconnectLocked() }
    }

    suspend fun forget() = withContext(Dispatchers.IO) {
        mutex.withLock {
            disconnectLocked()
            keyStore.clear()
            _state.value = State.NeedsPairing
            logBus.warn("已删除无线调试配对身份")
        }
    }

    fun refreshState() {
        if (_state.value is State.Pairing || _state.value is State.Connecting) return
        _state.value = when {
            !isSupported() -> State.Unsupported
            !hasLocalNetworkPermission() -> State.NeedsLocalNetworkPermission
            isConnected() -> State.Connected
            !keyStore.hasIdentity() -> State.NeedsPairing
            else -> State.Disconnected
        }
    }

    /** 打开一条一次性 shell 流；调用方负责关闭。 */
    fun openShellStream(command: String): AdbStream? {
        if (!isSupported()) return null
        val connection = getAdbConnection()
        if (connection == null || !isConnected()) {
            _state.value = if (keyStore.hasIdentity()) State.Disconnected else State.NeedsPairing
            return null
        }
        return runCatching {
            connection.open("shell,raw:$command")
        }.onFailure { throwable ->
            logBus.warn("无线调试打开 shell 失败: ${throwable.message}")
            if (throwable is AdbPairingRequiredException || throwable is AdbAuthenticationFailedException) {
                _state.value = State.NeedsPairing
            } else {
                _state.value = State.Disconnected
            }
        }.getOrNull()
    }

    private fun connectLocked(timeoutMs: Long, quiet: Boolean = false): Boolean {
        if (!isSupported()) {
            _state.value = State.Unsupported
            return false
        }
        if (!hasLocalNetworkPermission()) {
            _state.value = State.NeedsLocalNetworkPermission
            return false
        }
        if (isConnected()) {
            _state.value = State.Connected
            return true
        }
        _state.value = State.Connecting
        return runCatching {
            setTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            connectTls(context, timeoutMs)
        }.fold(
            onSuccess = { connected ->
                if (connected && isConnected()) {
                    _state.value = State.Connected
                    logBus.success("无线调试已连接")
                    true
                } else {
                    _state.value = if (keyStore.hasIdentity()) State.Disconnected else State.NeedsPairing
                    false
                }
            },
            onFailure = { throwable ->
                handleConnectFailure(throwable, quiet)
                false
            },
        )
    }

    private fun handleConnectFailure(throwable: Throwable, quiet: Boolean = false) {
        when (throwable) {
            is AdbPairingRequiredException,
            is AdbAuthenticationFailedException,
            -> {
                if (!quiet) logBus.warn("无线调试需要重新配对: ${throwable.message}")
                _state.value = State.NeedsPairing
            }

            is InterruptedException -> {
                if (!quiet) logBus.warn("无线调试连接超时")
                _state.value = State.Disconnected
            }

            else -> {
                if (!quiet) logBus.warn("无线调试连接失败: ${throwable.message}")
                _state.value = State.Error(throwable.message ?: "连接失败")
            }
        }
    }

    private fun disconnectLocked() {
        runCatching { getAdbConnection()?.close() }
        _state.value = if (keyStore.hasIdentity()) State.Disconnected else State.NeedsPairing
    }

    private fun initialState(): State = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> State.Unsupported
        !LocalNetworkPermission.isGranted(context) -> State.NeedsLocalNetworkPermission
        !keyStore.hasIdentity() -> State.NeedsPairing
        else -> State.Disconnected
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val DEFAULT_DISCOVERY_TIMEOUT_MS = 8_000L
    }
}
