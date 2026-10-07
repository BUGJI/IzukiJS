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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.service.DebugOverlayService
import com.benton.izukijs.service.FloatingWindowService
import com.benton.izukijs.service.IzukiAccessibilityService
import com.benton.izukijs.service.ScreenCaptureService
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SwitchRow
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

    var floatingEnabled by remember { mutableStateOf(FloatingWindowService.isActive) }
    var debugOverlayEnabled by remember { mutableStateOf(DebugOverlayService.isActive) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("权限与能力") },
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
                    "提示：脚本运行时可以收回非必要权限，以降低被风控识别的风险。按标注的优缺点选择当前脚本真正需要的权限即可。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(16.dp))

            AuxiliaryCard(
                title = "屏幕捕获",
                status = if (screenCaptureActive) "录制中" else "未开启",
                ready = screenCaptureActive,
                description = "授权一次后持续录屏，为截图 / 找图 / OCR 提供无限屏幕帧。",
                dimensions = listOf(
                    Dimension("精准度", "高", Rating.GOOD),
                    Dimension("风控", "低", Rating.GOOD),
                    Dimension("授权", "简单", Rating.GOOD),
                ),
                tip = "建议配合 蓝牙 HID / 无障碍 / Shizuku 等控制权限，形成「看屏 + 操作」闭环。",
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
                        ) { Text(if (screenCaptureActive) "录制中" else "授权并开始") }
                        if (screenCaptureActive) {
                            OutlinedButton(onClick = {
                                ScreenCaptureService.stop(context)
                                container.screenCapture.stop()
                            }) { Text("停止录制") }
                        }
                    }
                },
            )
            Spacer(Modifier.height(12.dp))

            CapabilityCard(
                title = "蓝牙 HID",
                status = if (hidReady) "已就绪" else "未就绪",
                ready = hidReady,
                description = "通过外部 HID 硬件（数位板狗）以绝对坐标注入输入，等同真实硬件。",
                dimensions = listOf(
                    Dimension("精准度", "高", Rating.GOOD),
                    Dimension("风控", "极低", Rating.GOOD),
                    Dimension("授权", "中", Rating.WARN),
                ),
                pros = listOf("等同真实硬件事件，最难被检测", "绝对坐标，点击 / 滑动精准"),
                cons = listOf("需外部 HID 硬件，便携性受限", "不支持截图与控件树"),
                action = {
                    CapabilityActions(
                        actionLabel = if (hidReady) "已就绪" else "去连接",
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
                title = "无障碍",
                status = if (accessibilityReady) "已就绪" else "未就绪",
                ready = accessibilityReady,
                description = "通过无障碍服务注入手势、读取控件树。无需额外安装，兼容性最好。",
                dimensions = listOf(
                    Dimension("精准度", "高", Rating.GOOD),
                    Dimension("风控", "中", Rating.WARN),
                    Dimension("授权", "简单", Rating.GOOD),
                ),
                pros = listOf("无需额外安装，开箱即用", "可读控件树，选择器定位精准"),
                cons = listOf("较易被风控识别", "API30 以下无法截图；键盘 / 文本能力有限"),
                action = {
                    CapabilityActions(
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
                            ({ IzukiAccessibilityService.instance?.disable() })
                        } else {
                            null
                        },
                    )
                },
            )
            Spacer(Modifier.height(12.dp))

            CapabilityCard(
                title = "Shizuku",
                status = when {
                    shizukuReady -> "已就绪"
                    !shizukuRunning -> "未运行服务"
                    else -> "未就绪"
                },
                ready = shizukuReady,
                description = "通过 Shizuku 以 ADB 权限注入输入、截图、执行 Shell。功能最强。",
                dimensions = listOf(
                    Dimension("精准度", "高", Rating.GOOD),
                    Dimension("风控", "中", Rating.WARN),
                    Dimension("授权", "复杂", Rating.BAD),
                ),
                pros = listOf("功能最全：手势 / 按键 / 文本 / 截图 / Shell", "无需 Root 即可获得高权限能力"),
                cons = listOf("需安装并常驻 Shizuku，重启后需重新启动服务", "连接断开即失效"),
                action = {
                    CapabilityActions(
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
                },
            )
            Spacer(Modifier.height(12.dp))

            CapabilityCard(
                title = "Root",
                status = if (rootReady) "已就绪" else "未就绪",
                ready = rootReady,
                description = "通过 Root 权限直接调用系统能力，与 Shizuku 能力相近。",
                dimensions = listOf(
                    Dimension("精准度", "高", Rating.GOOD),
                    Dimension("风控", "高", Rating.BAD),
                    Dimension("授权", "复杂", Rating.BAD),
                ),
                pros = listOf("最高权限，能力与 Shizuku 相当", "可直接执行 Shell 与系统级操作"),
                cons = listOf("需设备已 Root，门槛高", "权限过高，风控与安全风险最大"),
                action = {
                    CapabilityActions(
                        actionLabel = if (rootReady) "已就绪" else "检测 Root",
                        onAction = if (rootReady) null else ({ rootManager.refresh() }),
                        onRevoke = if (rootReady) ({ rootManager.revoke() }) else null,
                    )
                },
            )
            Spacer(Modifier.height(12.dp))

            AuxiliaryCard(
                title = "悬浮窗",
                status = if (floatingEnabled || debugOverlayEnabled) "已开启" else "未开启",
                ready = floatingEnabled || debugOverlayEnabled,
                description = "以悬浮窗显示控制条与调试面板，方便随时运行脚本、可视化控件树与 OCR 坐标。",
                dimensions = listOf(
                    Dimension("风控", "低", Rating.GOOD),
                    Dimension("授权", "简单", Rating.GOOD),
                ),
                tip = "建议配合任意控制权限使用，便于运行脚本与调试。",
                action = {
                    Column {
                        SwitchRow(
                            label = "显示悬浮控制条",
                            checked = floatingEnabled,
                        ) { checked ->
                            if (checked) {
                                withOverlay {
                                    FloatingWindowService.start(context, pinned = true)
                                    floatingEnabled = true
                                }
                            } else {
                                FloatingWindowService.stop(context)
                                floatingEnabled = false
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        SwitchRow(
                            label = "显示布局 / OCR 调试面板",
                            checked = debugOverlayEnabled,
                            description = "抓取布局 / OCR 时会自动隐藏，避免被截入画面。",
                        ) { checked ->
                            if (checked) {
                                withOverlay {
                                    DebugOverlayService.start(context)
                                    debugOverlayEnabled = true
                                }
                            } else {
                                DebugOverlayService.stop(context)
                                debugOverlayEnabled = false
                            }
                        }
                        if (!canDrawOverlays) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "需要在系统设置中授予「显示在其他应用上层」权限。",
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
                Text("撤销权限")
            }
        }
    }
}

private const val SHIZUKU_REQUEST_CODE = 1001
