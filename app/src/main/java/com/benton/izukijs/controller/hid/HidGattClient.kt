package com.benton.izukijs.controller.hid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.benton.izukijs.runtime.LogBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.UUID

enum class HidConnectionState { IDLE, SCANNING, CONNECTING, CONNECTED, READY, ERROR }

data class HidDevice(val name: String, val address: String, val rssi: Int)

/**
 * BLE GATT 客户端：扫描、连接外部数位板狗、订阅事件、下发控制帧。
 */
class HidGattClient(
    private val context: Context,
    private val logBus: LogBus,
) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _state = MutableStateFlow(HidConnectionState.IDLE)
    val state: StateFlow<HidConnectionState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<HidDevice>>(emptyList())
    val devices: StateFlow<List<HidDevice>> = _devices.asStateFlow()

    private val _connectedAddress = MutableStateFlow<String?>(null)
    val connectedAddress: StateFlow<String?> = _connectedAddress.asStateFlow()

    private val _dongleResolution = MutableStateFlow<Pair<Int, Int>?>(null)
    val dongleResolution: StateFlow<Pair<Int, Int>?> = _dongleResolution.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())

    private var bluetoothGatt: BluetoothGatt? = null
    private var controlChar: BluetoothGattCharacteristic? = null
    private var eventChar: BluetoothGattCharacteristic? = null

    /** Android GATT 同时只允许一个操作在途；控制帧全部排队，逐个等回调再发，避免丢帧。 */
    private val pendingWrites = ArrayDeque<ByteArray>()
    private var writeInFlight = false
    private var cccdWriteInFlight = false

    @Volatile
    private var autoReconnect = false

    /** 用户主动扫描中时不执行自动重连，避免 connect() 把扫描打断。 */
    @Volatile
    private var userScanning = false

    private val scanTimeout = Runnable { stopScan() }

    /** 按地址去重的扫描结果，仅在节流窗口结束后发布，避免每个广播包都重建并排序列表。 */
    private val discovered = LinkedHashMap<String, HidDevice>()

    @Volatile
    private var publishScheduled = false

    private val publishDevices = Runnable {
        publishScheduled = false
        _devices.value = synchronized(discovered) {
            discovered.values.sortedByDescending { it.rssi }
        }
    }

    /** 断开后仅记录一次，避免自动重连失败时反复刷屏。 */
    @Volatile
    private var disconnectLogged = false

    private var lastAddress: String? = null

    val isReady: Boolean get() = _state.value == HidConnectionState.READY

    // ---- 权限 ----

    fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return has(Manifest.permission.BLUETOOTH_SCAN) && has(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return has(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    // ---- 扫描 ----

    @SuppressLint("MissingPermission")
    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !hasPermissions()) {
            logBus.warn("无法扫描：蓝牙未开启或缺权限")
            return
        }
        handler.removeCallbacks(publishDevices)
        publishScheduled = false
        synchronized(discovered) { discovered.clear() }
        _devices.value = emptyList()
        addBondedDevices()
        _state.value = HidConnectionState.SCANNING
        userScanning = true
        // 不带服务 UUID 过滤：部分机型/控制器对 128 位 UUID 的硬件过滤匹配不可靠，
        // 会导致系统能扫到、App 却收不到结果。改为在回调里自行匹配。
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(null, settings, scanCallback) }
            .onFailure { logBus.error("扫描失败: ${it.message}") }
        handler.removeCallbacks(scanTimeout)
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        userScanning = false
        if (!hasPermissions()) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_state.value == HidConnectionState.SCANNING) {
            _state.value = HidConnectionState.IDLE
        }
    }

    /** 已配对过的狗即使当前没在广播（被系统 HID 主机占用连接）也能直接连接。 */
    @SuppressLint("MissingPermission")
    private fun addBondedDevices() {
        if (!hasPermissions()) return
        val bonded = runCatching { adapter?.bondedDevices }.getOrNull() ?: return
        synchronized(discovered) {
            for (device in bonded) {
                val name = runCatching { device.name }.getOrNull() ?: continue
                if (!name.startsWith(DEVICE_NAME_PREFIX, ignoreCase = true)) continue
                if (!discovered.containsKey(device.address)) {
                    discovered[device.address] = HidDevice(name, device.address, 0)
                }
            }
        }
        handler.removeCallbacks(publishDevices)
        publishScheduled = false
        publishDevices.run()
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = runCatching { device.name }.getOrNull() ?: "未知设备"
            val record = result.scanRecord
            val uuidMatch = record?.serviceUuids
                ?.any { it.uuid == HidProtocol.SERVICE_UUID } == true
            val nameMatch = name.startsWith(DEVICE_NAME_PREFIX, ignoreCase = true)
            if (!uuidMatch && !nameMatch) return
            synchronized(discovered) {
                discovered[device.address] = HidDevice(name, device.address, result.rssi)
            }
            schedulePublish()
        }

        override fun onScanFailed(errorCode: Int) {
            logBus.error("扫描失败，错误码 $errorCode")
            handler.removeCallbacks(scanTimeout)
            userScanning = false
            _state.value = HidConnectionState.IDLE
        }
    }

    private fun schedulePublish() {
        if (publishScheduled) return
        publishScheduled = true
        handler.postDelayed(publishDevices, PUBLISH_THROTTLE_MS)
    }

    // ---- 连接 ----

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (!hasPermissions()) {
            logBus.warn("缺少蓝牙权限")
            return
        }
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        autoReconnect = true
        lastAddress = address
        stopScan()
        _state.value = HidConnectionState.CONNECTING
        runCatching {
            bluetoothGatt?.close()
            bluetoothGatt = device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
            )
        }.onFailure { logBus.error("连接失败: ${it.message}") }
    }

    fun disconnect() {
        autoReconnect = false
        lastAddress = null
        closeGatt()
        _state.value = HidConnectionState.IDLE
        _connectedAddress.value = null
    }

    /** 自动重连：仅当之前连接成功过。 */
    @SuppressLint("MissingPermission")
    fun reconnectIfNeeded() {
        if (userScanning) {
            handler.postDelayed({ reconnectIfNeeded() }, RECONNECT_DELAY_MS)
            return
        }
        val address = lastAddress ?: return
        if (!hasPermissions()) return
        connect(address)
    }

    fun setLastAddress(address: String?) {
        lastAddress = address
        if (address != null) autoReconnect = true
    }

    private fun closeGatt() {
        runCatching { bluetoothGatt?.disconnect() }
        runCatching { bluetoothGatt?.close() }
        bluetoothGatt = null
        controlChar = null
        eventChar = null
        synchronized(pendingWrites) {
            pendingWrites.clear()
            writeInFlight = false
            cccdWriteInFlight = false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    disconnectLogged = false
                    _state.value = HidConnectionState.CONNECTED
                    _connectedAddress.value = gatt.device.address
                    synchronized(pendingWrites) {
                        pendingWrites.clear()
                        writeInFlight = false
                        cccdWriteInFlight = false
                    }
                    logBus.info("HID 狗已连接，协商 MTU…")
                    // 先请求 MTU，服务发现放到 onMtuChanged 里，避免同时占用 GATT 操作槽。
                    runCatching { gatt.requestMtu(517) }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (!disconnectLogged) {
                        logBus.warn("HID 狗连接断开")
                        disconnectLogged = true
                    }
                    closeGatt()
                    _connectedAddress.value = null
                    if (autoReconnect && lastAddress != null) {
                        _state.value = HidConnectionState.CONNECTING
                        handler.postDelayed({ reconnectIfNeeded() }, RECONNECT_DELAY_MS)
                    } else {
                        _state.value = HidConnectionState.IDLE
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(HidProtocol.SERVICE_UUID)
            if (service == null) {
                logBus.error("未找到 HID 控制服务")
                _state.value = HidConnectionState.ERROR
                return
            }
            controlChar = service.getCharacteristic(HidProtocol.CONTROL_UUID)
            eventChar = service.getCharacteristic(HidProtocol.EVENT_UUID)
            if (controlChar == null || eventChar == null) {
                logBus.error("HID 控制/事件特征缺失")
                _state.value = HidConnectionState.ERROR
                return
            }
            enableNotifications(gatt, eventChar!!)
            send(HidProtocol.handshake())
            _state.value = HidConnectionState.READY
            logBus.success("HID 狗就绪")
        }

        @Deprecated("Deprecated in Java")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            handleEvent(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleEvent(value)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            runCatching { gatt.discoverServices() }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            synchronized(pendingWrites) { writeInFlight = false }
            drainWrites()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            synchronized(pendingWrites) { cccdWriteInFlight = false }
            drainWrites()
        }
    }

    private fun handleEvent(data: ByteArray) {
        when (HidProtocol.kind(data)) {
            HidProtocol.EVT_HANDSHAKE_ACK -> {
                HidProtocol.parseHandshakeAck(data)?.let {
                    _dongleResolution.value = it.width to it.height
                    logBus.info("HID 狗 v${it.version}，数位板 ${it.width}x${it.height}")
                }
            }

            HidProtocol.EVT_STATUS -> {
                HidProtocol.parseStatus(data)?.let {
                    logBus.debug("HID 狗状态: 电量 ${it.battery}%，模式 ${it.mode}")
                }
            }

            HidProtocol.EVT_ERROR -> logBus.warn("HID 狗返回错误")
            else -> Unit
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        runCatching {
            gatt.setCharacteristicNotification(characteristic, true)
            val cccd = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG) ?: return
            synchronized(pendingWrites) { cccdWriteInFlight = true }
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
            if (!ok) {
                synchronized(pendingWrites) { cccdWriteInFlight = false }
            }
        }
    }

    // ---- 下发 ----

    /** 入队一帧控制数据。返回 true 表示已接受（并不代表已发送成功）。 */
    fun send(payload: ByteArray): Boolean {
        if (bluetoothGatt == null || controlChar == null || !hasPermissions()) return false
        synchronized(pendingWrites) {
            if (pendingWrites.size >= MAX_PENDING_WRITES) {
                logBus.warn("HID 发送队列已满，丢弃一帧")
                return false
            }
            pendingWrites.addLast(payload)
        }
        drainWrites()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun drainWrites() {
        val gatt = bluetoothGatt ?: return
        val control = controlChar ?: return
        if (!hasPermissions()) return
        val payload: ByteArray
        synchronized(pendingWrites) {
            if (writeInFlight || cccdWriteInFlight) return
            writeInFlight = true
            payload = pendingWrites.pollFirst() ?: run {
                writeInFlight = false
                return
            }
        }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    control,
                    payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                control.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                control.value = payload
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(control)
            }
        }.getOrDefault(false)
        if (!ok) {
            // GATT 忙：放回队首，稍后重试。
            synchronized(pendingWrites) {
                writeInFlight = false
                pendingWrites.addFirst(payload)
            }
            handler.postDelayed({ drainWrites() }, WRITE_RETRY_DELAY_MS)
        }
    }

    companion object {
        private val CLIENT_CHARACTERISTIC_CONFIG: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val RECONNECT_DELAY_MS = 2000L
        private const val SCAN_TIMEOUT_MS = 20_000L
        private const val PUBLISH_THROTTLE_MS = 400L
        private const val DEVICE_NAME_PREFIX = "Izuki"
        private const val WRITE_RETRY_DELAY_MS = 20L
        private const val MAX_PENDING_WRITES = 256
    }
}
