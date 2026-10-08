package com.benton.izukijs.ui.permission

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.service.DebugOverlayService
import com.benton.izukijs.service.FloatingWindowService
import com.benton.izukijs.service.IzukiAccessibilityService
import com.benton.izukijs.service.ScreenCaptureService
import com.benton.izukijs.ui.common.PageColumn
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SwitchRow
import com.benton.izukijs.ui.common.stableTopAppBarColors
import com.benton.izukijs.ui.rememberAppContainer
import com.benton.izukijs.ui.theme.LocalIzukiExtraColors

/**
 * 统一的「权限与能力」授权入口：控制后端、屏幕捕获与悬浮窗，全部是需要用户显式授权的项。
 * 按授权复杂度排序，简单项在前；核心权限标注精准度 / 风控等维度与优缺点，
 * 辅助权限（屏幕捕获、悬浮窗）只给出搭配建议。
 * 纯偏好（优先级、压缩、OCR 等）放在「设置 → 控制与视觉」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(onBack: () -> Unit, onOpenHid: () -> Unit) {
    val container = rememberAppContainer()
    val readyModes by container.controllerManager.readyModes.collectAsStateWithLifecycle()
    val screenCaptureActive by container.screenCapture.active.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val shizukuManager = container.shizukuManager
    val rootManager = container.rootManager

    val accessibilityReady = ControlMode.ACCESSIBILITY in readyModes
    val shizukuReady = ControlMode.SHIZUKU in readyModes
    val shizukuRunning = shizukuManager.isShizukuRunning()
    val rootReady = ControlMode.ROOT in readyModes
    val hidReady = ControlMode.HID in readyModes

    val floatingEnabled by FloatingWindowService.active.collectAsStateWithLifecycle()
    val debugOverlayEnabled by DebugOverlayService.active.collectAsStateWithLifecycle()
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    var pendingGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(context)) {
            canDrawOverlays = true
            val action = pendingGrant
            pendingGrant = null
            action?.invoke()
        }
    }

    fun withOverlay(onGranted: () -> Unit) {
        if (Settings.canDrawOverlays(context)) {
            onGranted()
            return
        }
        pendingGrant = onGranted
        runCatching {
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    val capturePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenCaptureService.start(context, result.resultCode, data)
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.run_permissions_title)) },
                scrollBehavior = scrollBehavior,
                colors = stableTopAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { shizukuManager.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh))
                    }
                },
            )
        },
    ) { padding ->
        PageColumn(modifier = Modifier.padding(padding).padding(16.dp)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Text(
                    stringResource(R.string.perm_tip),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(16.dp))

            AuxiliaryCard(
                title = stringResource(R.string.perm_screen_capture),
                status = stringResource(
                    if (screenCaptureActive) R.string.perm_status_recording else R.string.perm_status_off,
                ),
                ready = screenCaptureActive,
                description = stringResource(R.string.perm_capture_desc),
                dimensions = listOf(
                    Dimension(stringResource(R.string.dim_accuracy), stringResource(R.string.rating_high), Rating.GOOD),
                    Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_low), Rating.GOOD),
                    Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
                ),
                tip = stringResource(R.string.perm_capture_tip),
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                val manager =
                                    context.getSystemService(MediaProjectionManager::class.java)
                                runCatching {
                                    capturePermissionLauncher.launch(
                                        manager.createScreenCaptureIntent(),
                                    )
                                }
                            },
                            enabled = !screenCaptureActive,
                        ) {
                            Text(
                                stringResource(
                                    if (screenCaptureActive) R.string.perm_status_recording else R.string.perm_action_authorize_start,
                                ),
                            )
                        }
                        if (screenCaptureActive) {
                            OutlinedButton(onClick = {
                                ScreenCaptureService.stop(context)
                                container.screenCapture.stop()
                            }) { Text(stringResource(R.string.perm_action_stop_recording)) }
                        }
                    }
                },
            )
            Spacer(Modifier.height(12.dp))

            CapabilityCard(
                title = stringResource(R.string.control_hid),
                status = stringResource(if (hidReady) R.string.common_ready else R.string.common_not_ready),
                ready = hidReady,
                description = stringResource(R.string.perm_hid_desc),
                dimensions = listOf(
                    Dimension(stringResource(R.string.dim_accuracy), stringResource(R.string.rating_high), Rating.GOOD),
                    Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_very_low), Rating.GOOD),
                    Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_medium), Rating.WARN),
                ),
                pros = listOf(
                    stringResource(R.string.perm_hid_pro_1),
                    stringResource(R.string.perm_hid_pro_2),
                ),
                cons = listOf(
                    stringResource(R.string.perm_hid_con_1),
                    stringResource(R.string.perm_hid_con_2),
                ),
                action = {
                    CapabilityActions(
                        actionLabel = stringResource(if (hidReady) R.string.common_ready else R.string.perm_action_connect),
                        onAction = onOpenHid,
                        onRevoke = if (hidReady) {
                            ({ container.hidManager.revoke() })
                        } else {
                            null
                        },
                    )
                },
            )
            Spacer(Modifier.height(12.dp))

            CapabilityCard(
                title = stringResource(R.string.control_accessibility),
                status = stringResource(if (accessibilityReady) R.string.common_ready else R.string.common_not_ready),
                ready = accessibilityReady,
                description = stringResource(R.string.perm_accessibility_desc),
                dimensions = listOf(
                    Dimension(stringResource(R.string.dim_accuracy), stringResource(R.string.rating_high), Rating.GOOD),
                    Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_medium), Rating.WARN),
                    Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
                ),
                pros = listOf(
                    stringResource(R.string.perm_accessibility_pro_1),
                    stringResource(R.string.perm_accessibility_pro_2),
                ),
                cons = listOf(
                    stringResource(R.string.perm_accessibility_con_1),
                    stringResource(R.string.perm_accessibility_con_2),
                ),
                action = {
                    CapabilityActions(
                        actionLabel = stringResource(
                            if (accessibilityReady) R.string.perm_status_on else R.string.perm_action_open,
                        ),
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
                            ({ IzukiAccessibilityService.instance?.disable() })
                        } else {
                            null
                        },
                    )
                },
            )
            Spacer(Modifier.height(12.dp))

            CapabilityCard(
                title = stringResource(R.string.control_shizuku),
                status = stringResource(
                    when {
                        shizukuReady -> R.string.common_ready
                        !shizukuRunning -> R.string.perm_status_service_stopped
                        else -> R.string.common_not_ready
                    },
                ),
                ready = shizukuReady,
                description = stringResource(R.string.perm_shizuku_desc),
                dimensions = listOf(
                    Dimension(stringResource(R.string.dim_accuracy), stringResource(R.string.rating_high), Rating.GOOD),
                    Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_medium), Rating.WARN),
                    Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_complex), Rating.BAD),
                ),
                pros = listOf(
                    stringResource(R.string.perm_shizuku_pro_1),
                    stringResource(R.string.perm_shizuku_pro_2),
                ),
                cons = listOf(
                    stringResource(R.string.perm_shizuku_con_1),
                    stringResource(R.string.perm_shizuku_con_2),
                ),
                action = {
                    CapabilityActions(
                        actionLabel = stringResource(
                            when {
                                shizukuReady -> R.string.perm_status_authorized
                                !shizukuRunning -> R.string.perm_action_shizuku_not_running
                                else -> R.string.perm_action_authorize
                            },
                        ),
                        onAction = if (shizukuReady || !shizukuRunning) {
                            null
                        } else {
                            { shizukuManager.requestPermission(SHIZUKU_REQUEST_CODE) }
                        },
                        onRevoke = if (shizukuReady) ({ shizukuManager.revoke() }) else null,
                    )
                },
            )
            Spacer(Modifier.height(12.dp))

            CapabilityCard(
                title = stringResource(R.string.control_root),
                status = stringResource(if (rootReady) R.string.common_ready else R.string.common_not_ready),
                ready = rootReady,
                description = stringResource(R.string.perm_root_desc),
                dimensions = listOf(
                    Dimension(stringResource(R.string.dim_accuracy), stringResource(R.string.rating_high), Rating.GOOD),
                    Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_high), Rating.BAD),
                    Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_complex), Rating.BAD),
                ),
                pros = listOf(
                    stringResource(R.string.perm_root_pro_1),
                    stringResource(R.string.perm_root_pro_2),
                ),
                cons = listOf(
                    stringResource(R.string.perm_root_con_1),
                    stringResource(R.string.perm_root_con_2),
                ),
                action = {
                    CapabilityActions(
                        actionLabel = stringResource(
                            if (rootReady) R.string.common_ready else R.string.perm_action_detect_root,
                        ),
                        onAction = if (rootReady) null else ({ rootManager.refresh() }),
                        onRevoke = if (rootReady) ({ rootManager.revoke() }) else null,
                    )
                },
            )
            Spacer(Modifier.height(12.dp))

            AuxiliaryCard(
                title = stringResource(R.string.perm_floating_title),
                status = stringResource(
                    if (floatingEnabled || debugOverlayEnabled) R.string.perm_status_on else R.string.perm_status_off,
                ),
                ready = floatingEnabled || debugOverlayEnabled,
                description = stringResource(R.string.perm_floating_desc),
                dimensions = listOf(
                    Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_low), Rating.GOOD),
                    Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
                ),
                tip = stringResource(R.string.perm_floating_tip),
                action = {
                    Column {
                        SwitchRow(
                            label = stringResource(R.string.perm_floating_control),
                            checked = floatingEnabled,
                        ) { checked ->
                            if (checked) {
                                withOverlay { FloatingWindowService.start(context) }
                            } else {
                                FloatingWindowService.stop(context)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        SwitchRow(
                            label = stringResource(R.string.perm_floating_debug),
                            checked = debugOverlayEnabled,
                            description = stringResource(R.string.perm_floating_debug_desc),
                        ) { checked ->
                            if (checked) {
                                withOverlay { DebugOverlayService.start(context) }
                            } else {
                                DebugOverlayService.stop(context)
                            }
                        }
                        if (!canDrawOverlays) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.perm_overlay_needed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        }
    }
}

private enum class Rating { GOOD, WARN, BAD }

private data class Dimension(val label: String, val value: String, val rating: Rating)

/**
 * 核心权限卡片：标题 + 状态 + 说明 + 维度标签 + 优缺点 + 操作区。
 */
@Composable
private fun CapabilityCard(
    title: String,
    status: String,
    ready: Boolean,
    description: String,
    dimensions: List<Dimension>,
    pros: List<String>,
    cons: List<String>,
    action: @Composable () -> Unit,
) {
    PermissionCard(title, status, ready, description, dimensions, action) {
        ProsCons(pros = pros, cons = cons)
    }
}

/**
 * 辅助权限卡片：只给维度标签与搭配建议，不列缺点。
 */
@Composable
private fun AuxiliaryCard(
    title: String,
    status: String,
    ready: Boolean,
    description: String,
    dimensions: List<Dimension>,
    tip: String,
    action: @Composable () -> Unit,
) {
    PermissionCard(title, status, ready, description, dimensions, action) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                tip,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    status: String,
    ready: Boolean,
    description: String,
    dimensions: List<Dimension>,
    action: @Composable () -> Unit,
    body: @Composable () -> Unit,
) {
    val extra = LocalIzukiExtraColors.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(if (ready) extra.success else MaterialTheme.colorScheme.outline, CircleShape),
                )
                Spacer(Modifier.size(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ready) extra.success else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                dimensions.forEach { DimensionTag(it) }
            }
            Spacer(Modifier.height(10.dp))
            body()
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

@Composable
private fun DimensionTag(dimension: Dimension) {
    val extra = LocalIzukiExtraColors.current
    val (container, content) = when (dimension.rating) {
        Rating.GOOD -> extra.successContainer to extra.success
        Rating.WARN -> extra.warningContainer to extra.warning
        Rating.BAD -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            "${dimension.label} ${dimension.value}",
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

@Composable
private fun ProsCons(pros: List<String>, cons: List<String>) {
    val extra = LocalIzukiExtraColors.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        pros.forEach { text ->
            Text("✓ $text", style = MaterialTheme.typography.bodySmall, color = extra.success)
        }
        cons.forEach { text ->
            Text("✕ $text", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun CapabilityActions(
    actionLabel: String,
    onAction: (() -> Unit)?,
    onRevoke: (() -> Unit)?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { onAction?.invoke() }, enabled = onAction != null) {
            Text(actionLabel)
        }
        if (onRevoke != null) {
            OutlinedButton(onClick = { onRevoke.invoke() }) {
                Text(stringResource(R.string.perm_action_revoke))
            }
        }
    }
}

private const val SHIZUKU_REQUEST_CODE = 1001
