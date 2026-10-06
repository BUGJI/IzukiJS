package com.benton.izukijs.ui.permission

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.service.IzukiAccessibilityService
import com.benton.izukijs.ui.rememberAppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardScreen(onBack: () -> Unit, onOpenHid: () -> Unit) {
    val container = rememberAppContainer()
    val readyModes by container.controllerManager.readyModes.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val shizukuManager = container.shizukuManager
    val rootManager = container.rootManager

    val accessibilityReady = ControlMode.ACCESSIBILITY in readyModes
    val shizukuReady = ControlMode.SHIZUKU in readyModes
    val shizukuRunning = shizukuManager.isShizukuRunning()
    val rootReady = ControlMode.ROOT in readyModes
    val hidReady = ControlMode.HID in readyModes

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("控制模式") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { shizukuManager.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Text(
                    "提示：脚本运行时可以收回非必要权限，以降低被风控识别的风险。只保留当前脚本真正需要的控制模式即可。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(16.dp))

            ModeCard(
                title = "无障碍",
                ready = accessibilityReady,
                description = ControlMode.ACCESSIBILITY.description,
                actionLabel = if (accessibilityReady) "已开启" else "去开启",
                onAction = if (accessibilityReady) {
                    null
                } else {
                    {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                },
                onRevoke = if (accessibilityReady) {
                    { IzukiAccessibilityService.instance?.disable() }
                } else {
                    null
                },
            )
            Spacer(Modifier.height(12.dp))

            ModeCard(
                title = "Shizuku",
                ready = shizukuReady,
                description = buildString {
                    append(ControlMode.SHIZUKU.description)
                    if (!shizukuRunning) append("\n\n当前未检测到运行中的 Shizuku 服务。")
                },
                actionLabel = when {
                    shizukuReady -> "已授权"
                    !shizukuRunning -> "未运行 Shizuku"
                    else -> "去授权"
                },
                onAction = if (shizukuReady || !shizukuRunning) {
                    null
                } else {
                    { shizukuManager.requestPermission(SHIZUKU_REQUEST_CODE) }
                },
                onRevoke = if (shizukuReady) ({ shizukuManager.revoke() }) else null,
            )
            Spacer(Modifier.height(12.dp))

            ModeCard(
                title = "Root",
                ready = rootReady,
                description = ControlMode.ROOT.description,
                actionLabel = if (rootReady) "已就绪" else "检测 Root",
                onAction = if (rootReady) null else ({ rootManager.refresh() }),
                onRevoke = if (rootReady) ({ rootManager.revoke() }) else null,
            )
            Spacer(Modifier.height(12.dp))

            ModeCard(
                title = "蓝牙 HID",
                ready = hidReady,
                description = ControlMode.HID.description,
                actionLabel = if (hidReady) "已就绪" else "去连接",
                onAction = onOpenHid,
                onRevoke = if (hidReady) ({ container.hidManager.revoke() }) else null,
            )
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    ready: Boolean,
    description: String,
    actionLabel: String,
    onAction: (() -> Unit)?,
    onRevoke: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "$title · ${if (ready) "已就绪" else "未就绪"}",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onAction?.invoke() }, enabled = onAction != null) {
                    Text(actionLabel)
                }
                if (onRevoke != null) {
                    OutlinedButton(onClick = { onRevoke.invoke() }) {
                        Text("撤销权限")
                    }
                }
            }
        }
    }
}

private const val SHIZUKU_REQUEST_CODE = 1001
