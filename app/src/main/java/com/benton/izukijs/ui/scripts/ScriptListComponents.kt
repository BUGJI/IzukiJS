package com.benton.izukijs.ui.scripts

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.core.fileProviderAuthority
import com.benton.izukijs.data.ScriptGroupMode
import com.benton.izukijs.data.ScriptImporter
import com.benton.izukijs.data.ScriptNameResolver
import com.benton.izukijs.data.ScriptSort
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.subscription.ScriptSubscription
import com.benton.izukijs.ui.appViewModel
import com.benton.izukijs.ui.common.ConfirmDialog
import com.benton.izukijs.ui.common.EmptyState
import com.benton.izukijs.ui.common.IzukiPageScaffold
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.PageMaxWidth
import com.benton.izukijs.ui.common.ScriptEnvDialog
import com.benton.izukijs.ui.common.ScriptLaunchViewModel
import com.benton.izukijs.ui.common.formatDateTime
import com.benton.izukijs.ui.common.intervalLabel
import com.benton.izukijs.ui.common.rememberAppHaptics
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScriptRow(
    script: ScriptInfo,
    subscription: ScriptSubscription?,
    tags: List<String>,
    pinned: Boolean,
    pinnedToTile: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    running: Boolean,
    runEnabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleTile: () -> Unit,
    onEditTags: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    ListItem(
        leadingContent = {
            // 选择模式下用勾选框替代置顶按钮，避免同时承载两种语义。
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = null)
            } else {
                IconButton(onClick = onTogglePin) {
                    Icon(
                        ImageVector.vectorResource(if (pinned) R.drawable.ic_pin else R.drawable.ic_pin_outline),
                        contentDescription = stringResource(
                            if (pinned) R.string.scripts_unpin else R.string.scripts_pin,
                        ),
                        tint = if (pinned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        },
        headlineContent = { Text(script.name) },
        supportingContent = {
            // 日期格式化与标签拼串只在数据变化时计算一次，避免每次重组重复做。
            val dateText = remember(script.updatedAt) { formatDateTime(script.updatedAt) }
            val tagText = remember(tags) {
                val shown = tags.take(3).joinToString("  ") { "#$it" }
                shown + if (tags.size > 3) " +${tags.size - 3}" else ""
            }
            val parts = buildList {
                add(dateText)
                if (tagText.isNotEmpty()) add(tagText)
                if (subscription != null) {
                    val badge = if (!subscription.enabled || subscription.intervalMinutes <= 0L) {
                        stringResource(R.string.scripts_subscription_badge_disabled)
                    } else {
                        stringResource(
                            R.string.scripts_subscription_badge,
                            intervalLabel(subscription.intervalMinutes),
                        )
                    }
                    add(
                        if (subscription.lastError.isNotBlank()) {
                            stringResource(R.string.scripts_subscription_failed, badge)
                        } else {
                            badge
                        },
                    )
                }
            }
            Text(parts.joinToString(" · "))
        },
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        trailingContent = if (selectionMode) {
            null
        } else {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (running) {
                        IconButton(onClick = onStop) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.common_stop),
                            )
                        }
                    } else {
                        IconButton(onClick = onRun, enabled = runEnabled) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = stringResource(R.string.common_run),
                                tint = if (runEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (pinnedToTile) {
                                                R.string.scripts_tile_unpin
                                            } else {
                                                R.string.scripts_tile_pin
                                            },
                                        ),
                                    )
                                },
                                onClick = { menuOpen = false; onToggleTile() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.scripts_manage_tags)) },
                                onClick = { menuOpen = false; onEditTags() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.scripts_settings)) },
                                onClick = { menuOpen = false; onEdit() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_export)) },
                                onClick = { menuOpen = false; onExport() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_delete)) },
                                onClick = { menuOpen = false; onDelete() },
                            )
                        }
                    }
                }
            }
        },
    )
}

/** 多选模式底部操作栏；破坏性操作使用错误色，与其它删除入口保持一致。 */
@Composable
internal fun ScriptSelectionBar(
    allPinned: Boolean,
    onTogglePin: () -> Unit,
    onEditTags: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    BottomAppBar {
        TextButton(onClick = onTogglePin) {
            Text(stringResource(if (allPinned) R.string.scripts_unpin else R.string.scripts_pin))
        }
        TextButton(onClick = onEditTags) {
            Text(stringResource(R.string.scripts_manage_tags))
        }
        TextButton(onClick = onExport) {
            Text(stringResource(R.string.common_export))
        }
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = onDelete,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text(stringResource(R.string.common_delete))
        }
    }
}

internal data class ScriptGroup(
    val key: String,
    val tag: String?,
    val scripts: List<ScriptInfo>,
)

@Composable
internal fun ScriptGroupHeader(tag: String?, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (tag == null) stringResource(R.string.scripts_untagged) else "#$tag",
            style = MaterialTheme.typography.titleSmall,
            color = if (tag == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun CheckedMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = {
            if (selected) Icon(Icons.Filled.Check, contentDescription = null)
        },
        onClick = onClick,
    )
}

/** 首次加载脚本列表时的骨架屏，缓解冷启动的空白感。 */
@Composable
internal fun ScriptsSkeleton() {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Column(modifier = Modifier.fillMaxSize()) {
        repeat(5) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShimmerBox(alpha = alpha, width = 20.dp, height = 20.dp)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    ShimmerBox(alpha = alpha, width = 140.dp, height = 16.dp)
                    Spacer(Modifier.height(8.dp))
                    ShimmerBox(alpha = alpha, width = 88.dp, height = 12.dp)
                }
                Spacer(Modifier.width(16.dp))
                ShimmerBox(alpha = alpha, width = 20.dp, height = 20.dp)
            }
        }
    }
}

@Composable
internal fun ShimmerBox(alpha: Float, width: Dp, height: Dp) {
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.5f),
                RoundedCornerShape(4.dp),
            ),
    )
}
