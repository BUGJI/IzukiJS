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
import android.os.SystemClock
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

    /** 固件回报的 HID 通道是否真正可用（链路已连 + 已加密）。 */
    private val _hidReady = MutableStateFlow(false)
    val hidReady: StateFlow<Boolean> = _hidReady.asStateFlow()

    private val _dongleResolution = MutableStateFlow<Pair<Int, Int>?>(null)
    val dongleResolution: StateFlow<Pair<Int, Int>?> = _dongleResolution.asStateFlow()

    /** 固件协议版本（来自 HANDSHAKE_ACK）；未握手为 0。 */
    @Volatile
    private var _protocolVersion = 0
    val protocolVersion: Int get() = _protocolVersion

    /** 固件是否支持 CMD_GESTURE（复杂轨迹 / 停顿）。 */
    fun supportsGesture(): Boolean = _protocolVersion >= HidProtocol.MIN_GESTURE_VERSION

    private val handler = Handler(Looper.getMainLooper())

    private var bluetoothGatt: BluetoothGatt? = null
    private var controlChar: BluetoothGattCharacteristic? = null
    private var eventChar: BluetoothGattCharacteristic? = null

    /** Android GATT 同时只允许一个操作在途；控制帧全部排队，逐个等回调再发，避免丢帧。 */
    private val pendingWrites = ArrayDeque<ByteArray>()
    private var writeInFlight = false
    private var cccdWriteInFlight = false

    /** Event CCCD 是否已订阅成功（决定能否收到固件的 STATUS / HANDSHAKE_ACK）。 */
    @Volatile
    private var eventSubscribed = false

    /** 最近一次尝试订阅 Event 的时刻，用于失败后的重试节流。 */
    @Volatile
    private var lastSubscribeTry = 0L

    /** 服务发现是否已完成一次；用于忽略重复/缓存触发的 discoverServices 回调。 */
    @Volatile
    private var servicesDiscovered = false

    /** 是否有一次服务发现正在进行，避免重复 discoverServices 造成 GATT 命令冲突。 */
    @Volatile
    private var discoveryInFlight = false

    /** 连续订阅失败次数；超过阈值就彻底重连，避免本地 GATT 客户端卡死。 */
    private var subscribeFailures = 0

    /** 订阅 Event 的延迟任务（放到服务发现回调之外执行，避开栈内待处理命令冲突）。 */
    private val subscribeRunnable = Runnable { subscribeEvent() }

    /** 订阅超时兜底：若 CCCD 写入回调始终不返回，主动失败重试或重连。 */
    private val subscribeTimeout = Runnable {
        if (!eventSubscribed) {
            logBus.warn("HID 事件订阅超时，重试")
            synchronized(pendingWrites) { cccdWriteInFlight = false }
            onSubscribeFailed()
        }
    }

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

    /** 最近一次发起连接的时刻，用于连接超时重试。 */
    @Volatile
    private var connectingSince = 0L

    val isReady: Boolean get() =
        _state.value == HidConnectionState.READY && _hidReady.value

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
        connectingSince = SystemClock.elapsedRealtime()
        _state.value = HidConnectionState.CONNECTING
        handler.removeCallbacks(subscribeRunnable)
        handler.removeCallbacks(subscribeTimeout)
        eventSubscribed = false
        servicesDiscovered = false
        discoveryInFlight = false
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

    /**
     * 看门狗调用：确保已记忆的设备保持连接。狗刚通电、或连接回调丢失时，
     * 主动发起/重试连接；连接长时间卡住则强制重连。
     */
    fun ensureConnected() {
        val address = lastAddress ?: return
        if (!autoReconnect || userScanning || !hasPermissions()) return
        when (_state.value) {
            HidConnectionState.IDLE, HidConnectionState.ERROR -> connect(address)
            HidConnectionState.CONNECTING -> {
                val elapsed = SystemClock.elapsedRealtime() - connectingSince
                if (elapsed > CONNECT_TIMEOUT_MS) {
                    logBus.warn("HID 连接超时，重新连接")
                    closeGatt()
                    connect(address)
                }
            }
            HidConnectionState.READY, HidConnectionState.CONNECTED -> ensureSubscribed()
            else -> Unit
        }
    }

    /** 看门狗调用：服务发现完成后若仍未订阅 Event，补订阅一次（失败会自行重试）。 */
    private fun ensureSubscribed() {
        if (eventSubscribed || !servicesDiscovered) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSubscribeTry < SUBSCRIBE_RETRY_MS) return
        lastSubscribeTry = now
        scheduleSubscribe(0L)
    }

    /** 把订阅动作延迟到当前回调之外（下一主线程 tick 或指定延时），避开栈内待处理命令。 */
    private fun scheduleSubscribe(delayMs: Long) {
        handler.removeCallbacks(subscribeRunnable)
        if (delayMs <= 0L) handler.post(subscribeRunnable)
        else handler.postDelayed(subscribeRunnable, delayMs)
    }

    /**
     * 订阅 Event 的 CCCD。Android GATT 同一时刻只允许一个操作在途，因此写入 CCCD 前
     * 必须确认没有控制帧写入在进行；启动后由 [onDescriptorWrite] 或 [subscribeTimeout]
     * 决定成功还是重试/重连。
     */
    @SuppressLint("MissingPermission")
    private fun subscribeEvent() {
        if (eventSubscribed) return
        val gatt = bluetoothGatt ?: return
        val event = eventChar ?: return
        if (!hasPermissions()) return
        synchronized(pendingWrites) {
            if (writeInFlight || cccdWriteInFlight) {
                // 控制帧占用着 GATT 操作槽，稍后再订阅。
                scheduleSubscribe(SUBSCRIBE_RETRY_MS)
                return
            }
            cccdWriteInFlight = true
        }
        val started = runCatching {
            gatt.setCharacteristicNotification(event, true)
            val cccd = event.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
            if (cccd == null) {
                false
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }.getOrDefault(false)
        if (!started) {
            synchronized(pendingWrites) { cccdWriteInFlight = false }
            onSubscribeFailed()
        } else {
            handler.removeCallbacks(subscribeTimeout)
            handler.postDelayed(subscribeTimeout, SUBSCRIBE_TIMEOUT_MS)
        }
    }

    /** 订阅失败：小步重试，连续失败多次则彻底重连以清掉卡死的本地 GATT 客户端。 */
    private fun onSubscribeFailed() {
        if (eventSubscribed) return
        subscribeFailures++
        if (subscribeFailures >= MAX_SUBSCRIBE_FAILURES) {
            logBus.warn("HID 事件订阅多次失败，重连设备")
            forceReconnect()
        } else {
            scheduleSubscribe(SUBSCRIBE_RETRY_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun forceReconnect() {
        val address = lastAddress ?: return
        subscribeFailures = 0
        closeGatt()
        _connectedAddress.value = null
        if (!autoReconnect) {
            _state.value = HidConnectionState.IDLE
            return
        }
        _state.value = HidConnectionState.CONNECTING
        connectingSince = SystemClock.elapsedRealtime()
        handler.postDelayed({ if (autoReconnect) connect(address) }, RECONNECT_DELAY_MS)
    }

    private fun closeGatt() {
        handler.removeCallbacks(subscribeRunnable)
        handler.removeCallbacks(subscribeTimeout)
        runCatching { bluetoothGatt?.disconnect() }
        runCatching { bluetoothGatt?.close() }
        bluetoothGatt = null
        controlChar = null
        eventChar = null
        eventSubscribed = false
        servicesDiscovered = false
        discoveryInFlight = false
        _hidReady.value = false
        _protocolVersion = 0
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
                    _hidReady.value = false
                    _protocolVersion = 0
                    eventSubscribed = false
                    lastSubscribeTry = 0L
                    servicesDiscovered = false
                    discoveryInFlight = false
                    subscribeFailures = 0
                    handler.removeCallbacks(subscribeRunnable)
                    handler.removeCallbacks(subscribeTimeout)
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
            discoveryInFlight = false
            // 已连接的设备服务来自缓存，discoverServices 可能被重复触发；只处理第一次。
            if (servicesDiscovered) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logBus.error("服务发现失败: $status")
                _state.value = HidConnectionState.ERROR
                return
            }
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
            servicesDiscovered = true
            _state.value = HidConnectionState.READY
            logBus.success("HID 狗已连接，订阅事件通道…")
            // 关键：不要在 discoverServices 的回调栈内立刻写 CCCD。重连时服务来自缓存，
            // 发现回调几乎与请求同步返回，此时蓝牙栈内仍有未清理的发现命令，直接写
            // CCCD 会被拒绝（bta_gattc_enqueue: already has a pending command），
            // 之后回调不再返回，Event 永远订阅不上 → HID 一直"未就绪"。
            scheduleSubscribe(SUBSCRIBE_DELAY_MS)
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
            // 部分机型会重复回调 onMtuChanged；只发起一次服务发现，避免 GATT 命令冲突。
            if (servicesDiscovered || discoveryInFlight) return
            discoveryInFlight = true
            runCatching { gatt.discoverServices() }
                .onFailure {
                    discoveryInFlight = false
                    logBus.error("服务发现失败: ${it.message}")
                }
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
            handler.removeCallbacks(subscribeTimeout)
            synchronized(pendingWrites) { cccdWriteInFlight = false }
            if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    eventSubscribed = true
                    subscribeFailures = 0
                    logBus.info("HID 事件通道已订阅")
                    // 订阅成功后再握手，确保 HANDSHAKE_ACK / STATUS 能被收到。
                    send(HidProtocol.handshake())
                } else {
                    logBus.warn("HID 事件订阅被拒: $status")
                    onSubscribeFailed()
                }
            }
            drainWrites()
        }
    }

    private fun handleEvent(data: ByteArray) {
        when (HidProtocol.kind(data)) {
            HidProtocol.EVT_HANDSHAKE_ACK -> {
                HidProtocol.parseHandshakeAck(data)?.let {
                    _protocolVersion = it.version
                    _dongleResolution.value = it.width to it.height
                    logBus.info("HID 狗 v${it.version}，数位板 ${it.width}x${it.height}")
                }
            }

            HidProtocol.EVT_STATUS -> {
                HidProtocol.parseStatus(data)?.let {
                    _hidReady.value = it.hidReady
                    logBus.info(
                        "HID 状态: 链路=${it.linkUp} 加密=${it.encrypted} " +
                            "HID就绪=${it.hidReady} App=${it.appReady}",
                    )
                }
            }

            HidProtocol.EVT_ERROR -> {
                val code = HidProtocol.parseError(data)
                logBus.warn("HID 狗返回错误: ${code?.let { HidProtocol.errorText(it) } ?: "未知"}")
            }
            else -> Unit
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
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val SUBSCRIBE_RETRY_MS = 2000L
        private const val SUBSCRIBE_DELAY_MS = 150L
        private const val SUBSCRIBE_TIMEOUT_MS = 1500L
        private const val MAX_SUBSCRIBE_FAILURES = 3
        private const val SCAN_TIMEOUT_MS = 20_000L
        private const val PUBLISH_THROTTLE_MS = 400L
        private const val DEVICE_NAME_PREFIX = "Izuki"
        private const val WRITE_RETRY_DELAY_MS = 20L
        private const val MAX_PENDING_WRITES = 256
    }
}
