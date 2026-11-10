package com.benton.izukijs.ui.permission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.benton.izukijs.R
import com.benton.izukijs.ui.theme.LocalIzukiExtraColors

/**
 * 顶部状态头：并排展示「读取」「操作」两个维度的整体授权状态。
 */
@Composable
internal fun PermissionStatusHeader(readReady: Boolean, operateReady: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PermissionStatusPill(
            label = stringResource(R.string.perm_group_read),
            ready = readReady,
            modifier = Modifier.weight(1f),
        )
        PermissionStatusPill(
            label = stringResource(R.string.perm_group_operate),
            ready = operateReady,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun PermissionStatusPill(label: String, ready: Boolean, modifier: Modifier = Modifier) {
    val extra = LocalIzukiExtraColors.current
    val container = if (ready) extra.successContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (ready) extra.success else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = content)
        Text(
            stringResource(if (ready) R.string.perm_status_authorized else R.string.perm_status_unauthorized),
            style = MaterialTheme.typography.titleMedium,
            color = content,
        )
    }
}

/**
 * 权限能力介绍：标题、说明、维度标签、优缺点或搭配建议。空字段自动隐藏。
 */
internal data class PermissionInfo(
    val title: String,
    val description: String,
    val dimensions: List<Dimension> = emptyList(),
    val pros: List<String> = emptyList(),
    val cons: List<String> = emptyList(),
    val tip: String? = null,
)

/**
 * 权限卡片：标题 + 状态 + 说明入口（?）+ 操作区。详细介绍在 [PermissionInfoSheet] 中展开。
 */
@Composable
internal fun PermissionCard(
    info: PermissionInfo,
    status: String,
    ready: Boolean,
    onShowInfo: () -> Unit,
    action: @Composable () -> Unit,
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
                Text(info.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ready) extra.success else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                InfoButton(onClick = onShowInfo)
            }
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

/** 卡片右上角的「?」按钮，点击查看该权限能力的详细介绍。 */
@Composable
internal fun InfoButton(onClick: () -> Unit) {
    val label = stringResource(R.string.perm_detail_open)
    // 外层撑到 48dp 作为触控热区（符合 Material 最小交互尺寸），视觉圆形仍保持 24dp。
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "?",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 权限能力介绍弹层：从底部升起，便于展示较长的说明与优缺点。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PermissionInfoSheet(info: PermissionInfo, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(info.title, style = MaterialTheme.typography.titleLarge)
            Text(
                info.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (info.dimensions.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    info.dimensions.forEach { DimensionTag(it) }
                }
            }
            if (info.pros.isNotEmpty() || info.cons.isNotEmpty()) {
                ProsCons(pros = info.pros, cons = info.cons)
            }
            info.tip?.let { tip ->
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
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) { Text(stringResource(R.string.perm_detail_close)) }
        }
    }
}

internal enum class Rating { GOOD, WARN, BAD }

internal data class Dimension(val label: String, val value: String, val rating: Rating)

@Composable
internal fun DimensionTag(dimension: Dimension) {
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
internal fun ProsCons(pros: List<String>, cons: List<String>) {
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

/** 系统授予项没有应用内开关，提供「前往设置 / 管理」入口，避免开关被点击后回弹的误导。 */
@Composable
internal fun SystemPermissionAction(granted: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Text(
            stringResource(
                if (granted) R.string.perm_action_manage_settings else R.string.perm_action_open_settings,
            ),
        )
    }
}

@Composable
internal fun CapabilityActions(
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

internal const val SHIZUKU_REQUEST_CODE = 1001
