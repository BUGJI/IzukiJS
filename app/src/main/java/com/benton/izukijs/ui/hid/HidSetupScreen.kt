package com.benton.izukijs.ui.hid

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.controller.hid.HidConnectionState
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
    val resolution by client.dongleResolution.collectAsStateWithLifecycle()

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
            Toast.makeText(context, "需要蓝牙权限才能扫描设备", Toast.LENGTH_SHORT).show()
        }
    }

    fun startScan() {
        if (!client.isBluetoothEnabled()) {
            Toast.makeText(context, "请先开启蓝牙", Toast.LENGTH_SHORT).show()
            return
        }
        if (client.hasPermissions()) manager.startScan() else permissionLauncher.launch(requiredPermissions)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("蓝牙 HID") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { if (state == HidConnectionState.SCANNING) manager.stopScan() else startScan() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "扫描")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("连接状态", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(stateText(state), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    connected?.let {
                        Spacer(Modifier.height(4.dp))
                        Text("已连接: $it")
                    }
                    resolution?.let { (w, h) ->
                        Spacer(Modifier.height(4.dp))
                        Text("数位板分辨率: $w x $h")
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { startScan() }) { Text("扫描设备") }
                        if (connected != null) {
                            OutlinedButton(onClick = { manager.disconnect() }) { Text("断开") }
                        }
                        if (state == HidConnectionState.READY) {
                            OutlinedButton(onClick = {
                                val dm = context.resources.displayMetrics
                                container.hidManager.client.send(
                                    com.benton.izukijs.controller.hid.HidProtocol.tap(
                                        dm.widthPixels / 2,
                                        dm.heightPixels / 2,
                                        50,
                                    ),
                                )
                                Toast.makeText(context, "已发送中心点击", Toast.LENGTH_SHORT).show()
                            }) { Text("测试点击") }
                        }
                    }
                }
            }

            if (devices.isEmpty()) {
                Text(
                    "未发现设备。请确认狗的电源与广播已开启，点击右上角扫描。",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(devices, key = { it.address }) { device ->
                        ListItem(
                            headlineContent = { Text(device.name) },
                            supportingContent = { Text("${device.address} · RSSI ${device.rssi}") },
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

private fun stateText(state: HidConnectionState): String = when (state) {
    HidConnectionState.IDLE -> "未连接"
    HidConnectionState.SCANNING -> "扫描中…"
    HidConnectionState.CONNECTING -> "连接中…"
    HidConnectionState.CONNECTED -> "已连接，发现服务…"
    HidConnectionState.READY -> "已就绪"
    HidConnectionState.ERROR -> "连接错误"
}
