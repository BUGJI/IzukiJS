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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.BuildConfig
import com.benton.izukijs.R
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.ui.appViewModel
import com.benton.izukijs.ui.common.ConfirmDialog
import com.benton.izukijs.ui.common.IzukiPageScaffold
import com.benton.izukijs.ui.common.PageColumn
import com.benton.izukijs.ui.common.ScriptEnvDialog
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.findActivity
import com.benton.izukijs.ui.common.formatDateTime
import com.benton.izukijs.ui.common.localizedName
import com.benton.izukijs.ui.common.rememberAppHaptics
import com.benton.izukijs.ui.common.stableTopAppBarColors
import com.benton.izukijs.ui.pip.PipController
import com.benton.izukijs.ui.pip.PipHost
import com.benton.izukijs.ui.pip.PipRequest
import com.benton.izukijs.ui.theme.LocalIzukiExtraColors
import com.benton.izukijs.virtualscreen.VirtualScreenState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunScreen(
    onOpenSetup: () -> Unit,
    onOpenScripts: () -> Unit,
    isActivePage: Boolean = true,
) {
    val context = LocalContext.current
    val haptics = rememberAppHaptics()
    val extra = LocalIzukiExtraColors.current
    val viewModel: RunViewModel = appViewModel { RunViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val envDialog by viewModel.envDialog.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    val primary = ui.recentItems.firstOrNull()

    val pipHost = context.findActivity() as? PipHost
    val activeVirtualScreen = ui.virtualScreenState as? VirtualScreenState.Active
    val pipEligible = isActivePage &&
        ui.running &&
        activeVirtualScreen != null &&
        ui.previewAttached &&
        ui.virtualScreenSettings.pipOnHome &&
        pipHost != null &&
        PipController.isSupported(context)
    LaunchedEffect(pipEligible, activeVirtualScreen?.resolution) {
        val host = pipHost ?: return@LaunchedEffect
        val target = host as? android.app.Activity ?: return@LaunchedEffect
        val resolution = activeVirtualScreen?.resolution
        if (pipEligible && resolution != null) {
            val request = PipRequest(resolution)
            host.pipRequest = request
            PipController.updateParams(target, autoEnter = true, request = request)
        } else {
            host.pipRequest = null
            PipController.updateParams(
                target,
                autoEnter = false,
                request = PipRequest(resolution ?: ui.virtualScreenSettings.resolution),
            )
        }
    }

    // 读取 / 操作两维度的就绪判定与「权限与能力」页保持一致：任一后端就绪即视为该维度已授权。
    val accessibilityReady = ControlMode.ACCESSIBILITY in ui.readyModes
    val shizukuReady = ControlMode.SHIZUKU in ui.readyModes
    val rootReady = ControlMode.ROOT in ui.readyModes
    val hidReady = ControlMode.HID in ui.readyModes
    val readReady = ui.screenCaptureActive || accessibilityReady || shizukuReady || rootReady
    val operateReady = hidReady || accessibilityReady || shizukuReady || rootReady

    // 首页展示已授权的权限清单，按「屏幕录制 / 蓝牙 HID / 无障碍 / Shizuku / Root」顺序拼接为一行。
    val grantedCapabilities = listOfNotNull(
        if (ui.screenCaptureActive) stringResource(R.string.run_capture_label) else null,
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

    IzukiPageScaffold(
        title = { Text(stringResource(R.string.app_name)) },
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
                if (ui.running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(extra.success, CircleShape),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            ui.runningScript.orEmpty(),
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
                    if (activeVirtualScreen != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.virtual_screen_run_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.run_idle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            primary?.let {
                                haptics.tap()
                                viewModel.requestRun(it.script)
                            }
                        },
                        enabled = !ui.running && primary != null,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.run_recent))
                    }
                    OutlinedButton(
                        onClick = {
                            haptics.reject()
                            viewModel.stop()
                        },
                        enabled = ui.running,
                    ) { Text(stringResource(R.string.common_stop)) }
                }
                val runState = ui.runState
                if (ui.running && runState.isNotEmpty()) {
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
                if (ui.scriptsLoaded && ui.scripts.isEmpty()) {
                    TextButton(onClick = onOpenScripts) { Text(stringResource(R.string.run_no_scripts)) }
                }
            }

            SectionCard(
                title = stringResource(R.string.run_quick_title),
                trailing = {
                    if (ui.recentItems.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) {
                            Text(stringResource(R.string.run_quick_clear))
                        }
                    }
                },
            ) {
                when {
                    // 首次进入脚本列表仍在异步加载，先留空避免闪一下空状态。
                    !ui.scriptsLoaded -> Unit
                    ui.recentItems.isEmpty() -> Text(
                        stringResource(R.string.run_quick_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> ui.recentItems.forEach { item ->
                        QuickRunRow(
                            item = item,
                            enabled = !ui.running,
                            onRun = {
                                haptics.tap()
                                viewModel.requestRun(item.script)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    envDialog?.let { dialog ->
        ScriptEnvDialog(
            scriptName = dialog.script.name,
            fields = dialog.fields,
            initial = dialog.initial,
            onDismiss = viewModel::dismissEnv,
            onConfirm = viewModel::confirmEnv,
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.run_quick_clear),
            message = stringResource(R.string.run_quick_clear_confirm),
            destructive = true,
            onDismiss = { confirmClear = false },
            onConfirm = {
                haptics.reject()
                viewModel.clearHistory()
                confirmClear = false
            },
        )
    }
}


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
 * 授权状态驱动背景色与文字色：未配置为卡片默认底色与默认文字色、缺少能力为黄色、读取与操作均就绪为绿色，
 * 文字使用对应容器的前景色以适配亮 / 暗主题。
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
    val content = when (status) {
        PermissionStatus.COMPLETE -> extra.onSuccessContainer
        PermissionStatus.PARTIAL -> extra.onWarningContainer
        PermissionStatus.NONE -> MaterialTheme.colorScheme.onSurface
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
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
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
