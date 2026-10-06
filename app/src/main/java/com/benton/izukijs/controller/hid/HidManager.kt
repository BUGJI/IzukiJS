package com.benton.izukijs.controller.hid

import android.content.Context
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.runtime.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 蓝牙 HID 生命周期管理。连接就绪时注册 HidController，并同步数位板分辨率。
 */
class HidManager(
    private val context: Context,
    private val controllers: ControllerManager,
    private val logBus: LogBus,
    private val scope: CoroutineScope,
) {

    val client = HidGattClient(context, logBus)

    private val controller = HidController(client)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init() {
        val lastAddress = prefs.getString(KEY_LAST_ADDRESS, null)
        if (lastAddress != null) client.setLastAddress(lastAddress)

        scope.launch {
            client.state.collectLatest { state ->
                if (state == HidConnectionState.READY) {
                    controllers.register(controller)
                    syncResolution()
                } else {
                    controllers.unregister(ControlMode.HID)
                }
            }
        }

        if (lastAddress != null && client.hasPermissions()) {
            scope.launch(Dispatchers.IO) { runCatching { client.reconnectIfNeeded() } }
        }
    }

    fun connect(address: String) {
        prefs.edit().putString(KEY_LAST_ADDRESS, address).apply()
        client.setLastAddress(address)
        client.connect(address)
    }

    fun disconnect() = client.disconnect()

    /** 撤回 HID：断开并清除记忆的设备。 */
    fun revoke() {
        prefs.edit().remove(KEY_LAST_ADDRESS).apply()
        client.disconnect()
    }

    fun startScan() = client.startScan()

    fun stopScan() = client.stopScan()

    fun syncResolution() {
        val metrics = context.resources.displayMetrics
        client.send(HidProtocol.setResolution(metrics.widthPixels, metrics.heightPixels))
    }

    private companion object {
        const val PREFS = "izukijs_hid"
        const val KEY_LAST_ADDRESS = "last_address"
    }
}
