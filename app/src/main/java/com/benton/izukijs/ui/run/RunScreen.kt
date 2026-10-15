package com.benton.izukijs.ui.run

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.BuildConfig
import com.benton.izukijs.R
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.model.EnvField
import com.benton.izukijs.model.ScriptEnvSpec
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.ui.common.PageColumn
import com.benton.izukijs.ui.common.ScriptEnvDialog
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.formatDateTime
import com.benton.izukijs.ui.common.localizedName
import com.benton.izukijs.ui.common.rememberAppHaptics
import com.benton.izukijs.ui.common.stableTopAppBarColors
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
    val haptics = rememberAppHaptics()
    val extra = LocalIzukiExtraColors.current
    val readyModes by container.controllerManager.readyModes.collectAsStateWithLifecycle()
    val screenCaptureActive by container.screenCapture.active.collectAsStateWithLifecycle()
    val running by container.scriptExecutionManager.running.collectAsStateWithLifecycle()
    val runningScript by container.scriptExecutionManager.runningScript.collectAsStateWithLifecycle()
    val envData by container.scriptEnvRepository.data.collectAsStateWithLifecycle()
    val recent by container.runHistoryRepository.recent.collectAsStateWithLifecycle()
    var scripts by remember { mutableStateOf<List<ScriptInfo>>(emptyList()) }
    var scriptsLoaded by remember { mutableStateOf(false) }
    var envDialog by remember { mutableStateOf<EnvDialogState?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    fun requestRun(script: ScriptInfo) {
        haptics.tap()
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

    // 脚本增删改（含编辑器保存）后仓库会自增 revision；据此重新拉取，保证快捷区与运行历史同步。
    val revision by container.scriptRepository.revision.collectAsStateWithLifecycle()
    LaunchedEffect(revision) {
        scripts = container.scriptRepository.listAsync()
        scriptsLoaded = true
    }

    // 运行历史里脚本可能已被删除 / 重命名，只保留仍然存在的。
    val recentItems = remember(recent, scripts) {
        val byId = scripts.associateBy { it.id }
        recent.mapNotNull { record -> byId[record.name]?.let { RunItem(it, record.at) } }
    }
    val primary = recentItems.firstOrNull()

    // 读取 / 操作两维度的就绪判定与「权限与能力」页保持一致：任一后端就绪即视为该维度已授权。
    val accessibilityReady = ControlMode.ACCESSIBILITY in readyModes
    val shizukuReady = ControlMode.SHIZUKU in readyModes
    val rootReady = ControlMode.ROOT in readyModes
    val hidReady = ControlMode.HID in readyModes
    val readReady = screenCaptureActive || accessibilityReady || shizukuReady || rootReady
    val operateReady = hidReady || accessibilityReady || shizukuReady || rootReady

    // 首页展示已授权的权限清单，按「屏幕录制 / 蓝牙 HID / 无障碍 / Shizuku / Root」顺序拼接为一行。
    val grantedCapabilities = listOfNotNull(
        if (screenCaptureActive) stringResource(R.string.run_capture_label) else null,
        if (hidReady) ControlMode.HID.localizedName() else null,
        if (accessibilityReady) ControlMode.ACCESSIBILITY.localizedName() else null,
        if (shizukuReady) ControlMode.SHIZUKU.localizedName() else null,
        if (rootReady) ControlMode.ROOT.localizedName() else null,
    )
    val grantedText = if (grantedCapabilities.isEmpty()) {
        stringResource(R.string.run_status_card_not_configured)
    } else {
        grantedCapabilities.joinToString(", ")
    }
    val permissionStatus = when {
        readReady && operateReady -> PermissionStatus.COMPLETE
        readReady || operateReady -> PermissionStatus.PARTIAL
        else -> PermissionStatus.NONE
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                scrollBehavior = scrollBehavior,
                colors = stableTopAppBarColors(),
            )
        },
    ) { padding ->
        PageColumn(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RunPermissionCard(
                status = permissionStatus,
                readReady = readReady,
                grantedText = grantedText,
                onClick = onOpenSetup,
            )

            DeviceInfoCard()

            SectionCard(stringResource(R.string.run_status_title)) {
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
                        stringResource(R.string.run_running),
                        color = extra.success,
                        style = MaterialTheme.typography.labelLarge,
                    )
                } else {
                    Text(
                        stringResource(R.string.run_idle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { primary?.let { requestRun(it.script) } },
                        enabled = !running && primary != null,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.run_recent))
                    }
                    OutlinedButton(
                        onClick = {
                            haptics.reject()
                            container.scriptExecutionManager.requestStop()
                        },
                        enabled = running,
                    ) { Text(stringResource(R.string.common_stop)) }
                }
                val runState = runningScript?.let { envData[it]?.state }.orEmpty()
                if (running && runState.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.run_vars_title), style = MaterialTheme.typography.labelLarge)
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
                if (scriptsLoaded && scripts.isEmpty()) {
                    TextButton(onClick = onOpenScripts) { Text(stringResource(R.string.run_no_scripts)) }
                }
            }

            SectionCard(
                title = stringResource(R.string.run_quick_title),
                trailing = {
                    if (recentItems.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) {
                            Text(stringResource(R.string.run_quick_clear))
                        }
                    }
                },
            ) {
                when {
                    // 首次进入脚本列表仍在异步加载，先留空避免闪一下空状态。
                    !scriptsLoaded -> Unit
                    recentItems.isEmpty() -> Text(
                        stringResource(R.string.run_quick_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> recentItems.forEach { item ->
                        QuickRunRow(
                            item = item,
                            enabled = !running,
                            onRun = { requestRun(item.script) },
                        )
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

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.run_quick_clear)) },
            text = { Text(stringResource(R.string.run_quick_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    haptics.reject()
                    container.runHistoryRepository.clear()
                    confirmClear = false
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) }
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

private data class RunItem(val script: ScriptInfo, val at: Long)

@Composable
private fun QuickRunRow(
    item: RunItem,
    enabled: Boolean,
    onRun: () -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(item.script.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                stringResource(R.string.run_last_at, formatDateTime(item.at)),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                onClickLabel = stringResource(R.string.common_run),
            ) { onRun() },
    )
}

/** 首页权限概览的整体授权状态：完整 / 缺少一项 / 未授权。 */
private enum class PermissionStatus { COMPLETE, PARTIAL, NONE }

/**
 * 首页顶部权限概览卡片：内容居左，中部展示软件版本，底部以一行文字列出已授权的权限；
 * 右侧在未配置时提示进入配置、已配置时显示状态图标。整卡可点击进入「权限与能力」页。
 * 授权状态驱动背景色：未配置为卡片默认底色、缺少能力为黄色、读取与操作均就绪为绿色；文字始终为白色。
 */
@Composable
private fun RunPermissionCard(
    status: PermissionStatus,
    readReady: Boolean,
    grantedText: String,
    onClick: () -> Unit,
) {
    val extra = LocalIzukiExtraColors.current
    val container = when (status) {
        PermissionStatus.COMPLETE -> extra.successContainer
        PermissionStatus.PARTIAL -> extra.warningContainer
        PermissionStatus.NONE -> CardDefaults.cardColors().containerColor
    }
    val title = stringResource(
        when {
            status == PermissionStatus.NONE -> R.string.run_status_card_unauthorized
            status == PermissionStatus.COMPLETE -> R.string.run_status_card_complete
            !readReady -> R.string.run_status_card_missing_read
            else -> R.string.run_status_card_missing_operate
        },
    )
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = Color.White),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.run_status_card_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (status != PermissionStatus.NONE) {
                    Icon(
                        imageVector = if (status == PermissionStatus.COMPLETE) {
                            Icons.Filled.CheckCircle
                        } else {
                            Icons.Filled.Warning
                        },
                        contentDescription = null,
                    )
                } else {
                    Text(
                        stringResource(R.string.run_status_card_configure_hint),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(grantedText, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * 首页设备信息卡片：逐行展示设备参数，每行为「图标 + 标题 + 内容」。
 */
@Composable
private fun DeviceInfoCard() {
    val unknown = stringResource(R.string.run_device_unknown)
    val model = remember {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        when {
            model.isEmpty() -> manufacturer.ifEmpty { "" }
            manufacturer.isEmpty() || model.contains(manufacturer, ignoreCase = true) -> model
            else -> "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
        }
    }.ifEmpty { unknown }
    val androidVersion = stringResource(
        R.string.run_device_android_value,
        Build.VERSION.RELEASE.orEmpty().ifEmpty { unknown },
        Build.VERSION.SDK_INT,
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DeviceInfoRow(
                icon = ImageVector.vectorResource(R.drawable.ic_device_phone),
                title = stringResource(R.string.run_device_model),
                value = model,
            )
            DeviceInfoRow(
                icon = ImageVector.vectorResource(R.drawable.ic_android_logo),
                title = stringResource(R.string.run_device_android),
                value = androidVersion,
            )
        }
    }
}

@Composable
private fun DeviceInfoRow(icon: ImageVector, title: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
