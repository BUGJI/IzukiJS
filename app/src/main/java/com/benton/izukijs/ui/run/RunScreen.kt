package com.benton.izukijs.ui.run

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.model.EnvField
import com.benton.izukijs.model.ScriptEnvSpec
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.ui.common.ScriptEnvDialog
import com.benton.izukijs.ui.rememberAppContainer
import com.benton.izukijs.ui.theme.LocalIzukiExtraColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunScreen(
    onOpenSetup: () -> Unit,
    onOpenScripts: () -> Unit,
) {
    val container = rememberAppContainer()
    val scope = rememberCoroutineScope()
    val extra = LocalIzukiExtraColors.current
    val readyModes by container.controllerManager.readyModes.collectAsStateWithLifecycle()
    val screenCaptureActive by container.screenCapture.active.collectAsStateWithLifecycle()
    val running by container.scriptExecutionManager.running.collectAsStateWithLifecycle()
    val runningScript by container.scriptExecutionManager.runningScript.collectAsStateWithLifecycle()
    val envData by container.scriptEnvRepository.data.collectAsStateWithLifecycle()
    var scripts by remember { mutableStateOf<List<ScriptInfo>>(emptyList()) }
    var envDialog by remember { mutableStateOf<EnvDialogState?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    fun requestRun(script: ScriptInfo) {
        scope.launch {
            val source = container.scriptRepository.readAsync(script)
            val spec = ScriptEnvSpec.parse(source)
            val stored = container.scriptEnvRepository.values(script.id)
            if (spec.isEmpty && stored.isEmpty()) {
                container.scriptExecutionManager.run(script.name, source)
            } else {
                envDialog = EnvDialogState(
                    script = script,
                    source = source,
                    fields = spec.fields,
                    initial = spec.defaults() + stored,
                )
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { scripts = container.scriptRepository.listAsync() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("运行") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("运行状态", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (running) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(extra.success, CircleShape),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                runningScript.orEmpty(),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "正在运行",
                            color = extra.success,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    } else {
                        Text(
                            "当前未运行脚本",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { scripts.firstOrNull()?.let { requestRun(it) } },
                            enabled = !running && scripts.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("运行最近脚本")
                        }
                        OutlinedButton(
                            onClick = { container.scriptExecutionManager.requestStop() },
                            enabled = running,
                        ) { Text("停止") }
                    }
                    val runState = runningScript?.let { envData[it]?.state }.orEmpty()
                    if (running && runState.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("运行状态", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(4.dp))
                        runState.forEach { (key, value) ->
                            Text(
                                "$key = $value",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (scripts.isEmpty()) {
                        TextButton(onClick = onOpenScripts) { Text("还没有脚本，去脚本中心新建") }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("权限与能力", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onOpenSetup) { Text("配置") }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    if (screenCaptureActive) {
                                        extra.success
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    CircleShape,
                                ),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (screenCaptureActive) "屏幕捕获：录制中" else "屏幕捕获：未开启",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val modes = listOf(
                        "无障碍" to (ControlMode.ACCESSIBILITY in readyModes),
                        "Shizuku" to (ControlMode.SHIZUKU in readyModes),
                        "Root" to (ControlMode.ROOT in readyModes),
                        "蓝牙 HID" to (ControlMode.HID in readyModes),
                    )
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val columns = if (maxWidth >= 480.dp) 4 else 2
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            modes.chunked(columns).forEach { rowModes ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    rowModes.forEach { (label, ready) ->
                                        ModeStatus(
                                            label = label,
                                            ready = ready,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    repeat(columns - rowModes.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    envDialog?.let { state ->
        ScriptEnvDialog(
            scriptName = state.script.name,
            fields = state.fields,
            initial = state.initial,
            onDismiss = { envDialog = null },
            onConfirm = { values ->
                container.scriptEnvRepository.saveValues(state.script.id, values)
                container.scriptExecutionManager.run(state.script.name, state.source, values)
                envDialog = null
            },
        )
    }
}

private data class EnvDialogState(
    val script: ScriptInfo,
    val source: String,
    val fields: List<EnvField>,
    val initial: Map<String, String>,
)

@Composable
private fun ModeStatus(label: String, ready: Boolean, modifier: Modifier = Modifier) {
    val extra = LocalIzukiExtraColors.current
    val dotColor = if (ready) extra.success else MaterialTheme.colorScheme.error
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(dotColor, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (ready) "已就绪" else "未启用",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
