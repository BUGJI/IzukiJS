package com.benton.izukijs.controller.adb

import android.content.Context
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.permission.LocalNetworkPermission
import com.benton.izukijs.model.ControlMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 无线调试（ADB over Wi-Fi）后端的生命周期管理。
 *
 * 与 Shizuku / Root 并存：连接成功时向 [ControllerManager] 注册 [AdbController]，
 * 断开或配对失效时自动反注册。
 */
class AdbManager(
    context: Context,
    private val controllers: ControllerManager,
    private val logBus: LogBus,
    private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext
    private val keyStore = AdbKeyStore(appContext)
    private val connection = AdbConnectionManager(appContext, keyStore, logBus)
    private val shell = AdbShell(connection, logBus)
    private val controller = AdbController(appContext, shell, logBus) {
        controllers.targetDisplayId
    }

    /** 配对端口发现器；由设置页在配对期间启动 / 停止。 */
    val pairingDiscovery = AdbPairingDiscovery(appContext)

    val state: StateFlow<AdbConnectionManager.State> = connection.state

    init {
        scope.launch {
            connection.state.collect { state ->
                when (state) {
                    is AdbConnectionManager.State.Connected -> controllers.register(controller)
                    else -> controllers.unregister(ControlMode.ADB)
                }
            }
        }
    }

    fun init() {
        if (!isSupported()) {
            logBus.info("当前系统不支持无线调试（需要 Android 11+）")
            return
        }
        if (!keyStore.hasIdentity()) return
        if (!connection.hasLocalNetworkPermission()) {
            connection.refreshState()
            return
        }
        scope.launch(Dispatchers.IO) {
            if (connection.isReady()) return@launch
            val connected = connection.connectQuietly(AUTO_CONNECT_TIMEOUT_MS)
            if (connected) {
                logBus.success("无线调试已自动连接")
            } else {
                logBus.debug("无线调试未连接；可在权限页重新连接")
            }
        }
    }

    /** 用户主动刷新：重新探测状态，并在已配对时尝试连接。 */
    fun refresh() {
        if (!isSupported()) return
        scope.launch(Dispatchers.IO) {
            connection.refreshState()
            if (connection.hasLocalNetworkPermission() &&
                keyStore.hasIdentity() &&
                !connection.isReady()
            ) {
                connection.connect(AUTO_CONNECT_TIMEOUT_MS)
            }
        }
    }

    fun refreshState() = connection.refreshState()

    fun isSupported(): Boolean = connection.isSupported()

    fun isReady(): Boolean = connection.isReady()

    fun hasIdentity(): Boolean = connection.hasIdentity()

    fun hasLocalNetworkPermission(): Boolean = connection.hasLocalNetworkPermission()

    fun localNetworkPermissionRequired(): Boolean = LocalNetworkPermission.isRequired()

    fun preferredHost(): String? = AdbNetworkUtil.preferredIpv4Address()

    suspend fun pairAndConnect(host: String, port: Int, pairingCode: String): Boolean =
        connection.pairAndConnect(host, port, pairingCode)

    suspend fun connect(): Boolean = connection.connect()

    suspend fun connectManual(host: String, port: Int): Boolean =
        connection.connectManual(host, port)

    suspend fun disconnect() = connection.disconnectConnection()

    suspend fun forget() {
        pairingDiscovery.stop()
        connection.forget()
    }

    private companion object {
        const val AUTO_CONNECT_TIMEOUT_MS = 5_000L
    }
}
