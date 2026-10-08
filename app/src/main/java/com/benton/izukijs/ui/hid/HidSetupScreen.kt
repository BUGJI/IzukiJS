package com.benton.izukijs.ui.hid

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import com.benton.izukijs.controller.hid.HidProtocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.controller.hid.HidConnectionState
import com.benton.izukijs.ui.common.EmptyState
import com.benton.izukijs.ui.rememberAppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HidSetupScreen(onBack: () -> Unit) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val manager = container.hidManager
    val client = manager.client

    val state by client.state.collectAsStateWithLifecycle()
    val devices by client.devices.collectAsStateWithLifecycle()
    val connected by client.connectedAddress.collectAsStateWithLifecycle()
    val hidReady by client.hidReady.collectAsStateWithLifecycle()
    val resolution by client.dongleResolution.collectAsStateWithLifecycle()

    // HID 回环验证：把绝对坐标点击注入到检测靶点的真实屏幕坐标，命中即证明通路可用。
    val scope = rememberCoroutineScope()
    var targetCenter by remember { mutableStateOf<IntOffset?>(null) }
    var hit by remember { mutableStateOf(false) }
    var verifyState by remember { mutableStateOf(VerifyState.IDLE) }

    val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            manager.startScan()
        } else {
            Toast.makeText(context, context.getString(R.string.hid_need_bt_permission), Toast.LENGTH_SHORT).show()
        }
    }

    fun startScan() {
        if (!client.isBluetoothEnabled()) {
            Toast.makeText(context, context.getString(R.string.hid_enable_bt), Toast.LENGTH_SHORT).show()
            return
        }
        if (client.hasPermissions()) manager.startScan() else permissionLauncher.launch(requiredPermissions)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.control_hid)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { if (state == HidConnectionState.SCANNING) manager.stopScan() else startScan() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.hid_scan))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.hid_connection_status), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(stateText(state, hidReady), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    connected?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.hid_connected, it))
                    }
                    resolution?.let { (w, h) ->
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.hid_resolution, w, h))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // 检测靶点：验证时把绝对坐标点击注入到它真实的屏幕坐标，
                        // 只有事件穿过系统输入管线落到 App 自己身上才会命中原点。
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    if (hit) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable(enabled = verifyState == VerifyState.WAITING) { hit = true }
                                .onGloballyPositioned { coords ->
                                    // 直接用靶点中心算出它的屏幕像素坐标。
                                    val screen = coords.localToScreen(
                                        Offset(coords.size.width / 2f, coords.size.height / 2f),
                                    )
                                    targetCenter = IntOffset(screen.x.roundToInt(), screen.y.roundToInt())
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (hit) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = stringResource(R.string.hid_hit),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                        Column {
                            Text(stringResource(R.string.hid_target), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(
                                    when (verifyState) {
                                        VerifyState.IDLE -> R.string.hid_verify_idle
                                        VerifyState.WAITING -> R.string.hid_verify_waiting
                                        VerifyState.PASS -> R.string.hid_verify_pass
                                        VerifyState.FAIL -> R.string.hid_verify_fail
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = when (verifyState) {
                                    VerifyState.PASS -> MaterialTheme.colorScheme.primary
                                    VerifyState.FAIL -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { startScan() }) { Text(stringResource(R.string.hid_scan_devices)) }
                        if (connected != null) {
                            OutlinedButton(onClick = { manager.disconnect() }) { Text(stringResource(R.string.hid_disconnect)) }
                        }
                        if (hidReady) {
                            OutlinedButton(
                                enabled = verifyState != VerifyState.WAITING && targetCenter != null,
                                onClick = {
                                    val center = targetCenter ?: return@OutlinedButton
                                    hit = false
                                    verifyState = VerifyState.WAITING
                                    client.send(HidProtocol.tap(center.x, center.y, HID_VERIFY_TAP_MS))
                                    scope.launch {
                                        delay(HID_VERIFY_TIMEOUT_MS)
                                        if (verifyState == VerifyState.WAITING) {
                                            verifyState = if (hit) VerifyState.PASS else VerifyState.FAIL
                                        }
                                    }
                                },
                            ) { Text(stringResource(R.string.hid_verify)) }
                        }
                    }
                }
            }

            if (devices.isEmpty()) {
                EmptyState(
                    stringResource(R.string.hid_empty),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(devices, key = { it.address }) { device ->
                        ListItem(
                            headlineContent = { Text(device.name) },
                            supportingContent = { Text(stringResource(R.string.hid_device_meta, device.address, device.rssi)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { manager.connect(device.address) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun stateText(state: HidConnectionState, hidReady: Boolean): String = stringResource(
    when (state) {
        HidConnectionState.IDLE -> R.string.hid_state_idle
        HidConnectionState.SCANNING -> R.string.hid_state_scanning
        HidConnectionState.CONNECTING -> R.string.hid_state_connecting
        HidConnectionState.CONNECTED -> R.string.hid_state_connected
        HidConnectionState.READY ->
            if (hidReady) R.string.hid_state_ready else R.string.hid_state_ready_pending
        HidConnectionState.ERROR -> R.string.hid_state_error
    },
)

/** HID 回环验证状态。 */
private enum class VerifyState { IDLE, WAITING, PASS, FAIL }

/** 验证点击的按下时长与等待命中的超时。 */
private const val HID_VERIFY_TAP_MS = 60
private const val HID_VERIFY_TIMEOUT_MS = 1500L
