package com.benton.izukijs.controller.adb

import android.content.Context
import com.benton.izukijs.core.permission.LocalNetworkPermission
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 发现系统无线调试配对对话框临时开放的 `_adb-tls-pairing._tcp` 服务。
 *
 * 配对端口只在“使用配对码配对设备”对话框打开期间存在；UI 应在用户开始配对时
 * 调用 [start]，配对完成或取消后调用 [stop]。
 */
class AdbPairingDiscovery(context: Context) {

    data class Endpoint(val host: String, val port: Int)

    private val _endpoint = MutableStateFlow<Endpoint?>(null)
    val endpoint: StateFlow<Endpoint?> = _endpoint.asStateFlow()

    private val appContext = context.applicationContext
    private val lock = Any()
    private var running = false

    private val mdns = AdbMdns(
        appContext,
        AdbMdns.SERVICE_TYPE_TLS_PAIRING,
    ) { host, port ->
        if (port <= 0) {
            _endpoint.value = null
        } else if (host != null && AdbNetworkUtil.isLocalAddress(host)) {
            host.hostAddress?.let { _endpoint.value = Endpoint(it, port) }
        }
    }

    fun start() {
        if (!LocalNetworkPermission.isGranted(appContext)) {
            _endpoint.value = null
            return
        }
        synchronized(lock) {
            if (running) return
            running = true
        }
        _endpoint.value = null
        runCatching { mdns.start() }
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
        }
        runCatching { mdns.stop() }
        _endpoint.value = null
    }
}
