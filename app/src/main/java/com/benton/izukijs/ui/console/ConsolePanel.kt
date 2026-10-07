package com.benton.izukijs.ui.console

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.benton.izukijs.runtime.LogEntry
import com.benton.izukijs.runtime.LogLevel
import com.benton.izukijs.ui.common.EmptyState
import com.benton.izukijs.ui.common.displayColor
import com.benton.izukijs.ui.common.displayName
import com.benton.izukijs.ui.common.formatLogTime
import com.benton.izukijs.ui.common.shortTag
import kotlinx.coroutines.launch

@Composable
fun ConsolePanel(
    entries: List<LogEntry>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    showFilter: Boolean = true,
    showTimestamp: Boolean = true,
    showHeader: Boolean = true,
    onCollapse: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var enabledLevels by remember { mutableStateOf(LogLevel.entries.toSet()) }

    val visible = remember(entries, enabledLevels) {
        if (enabledLevels.size == LogLevel.entries.size) entries
        else entries.filter { it.level in enabledLevels }
    }

    // 仅在用户本来就停在底部时跟随；避免打断向上翻阅，也避免 animate 在高速输出下被反复重启。
    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty() && !listState.canScrollForward) {
            listState.scrollToItem(visible.lastIndex)
        }
    }

    // 是否停在底部，用于决定是否露出「回到最新」按钮。
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    // 记录最近一次处于底部时已看到的条目数，差值即上滑期间新增的未读条数。
    var seenCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(atBottom, visible.size) {
        if (atBottom) seenCount = visible.size
    }
    val unread = if (atBottom) 0 else (visible.size - seenCount).coerceAtLeast(0)

    fun copyAll() {
        if (visible.isEmpty()) return
        val text = visible.joinToString("\n") { it.toLine(showTimestamp) }
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText("log", text))
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (showHeader) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("控制台", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { copyAll() }, enabled = visible.isNotEmpty()) { Text("复制") }
                TextButton(onClick = onClear) { Text("清空") }
                if (onCollapse != null) {
                    IconButton(onClick = onCollapse) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起控制台")
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { copyAll() }, enabled = visible.isNotEmpty()) { Text("复制") }
                TextButton(onClick = onClear) { Text("清空") }
            }
        }

        if (showFilter) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LogLevel.entries.forEach { level ->
                    FilterChip(
                        selected = level in enabledLevels,
                        onClick = {
                            enabledLevels = if (level in enabledLevels) {
                                enabledLevels - level
                            } else {
                                enabledLevels + level
                            }
                        },
                        label = { Text(level.displayName()) },
                    )
                }
            }
        }

        HorizontalDivider()
        when {
            entries.isEmpty() -> EmptyState("暂无输出")
            visible.isEmpty() -> EmptyState("当前筛选下没有日志")
            else -> Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    items(visible, key = { it.id }) { entry ->
                        Text(
                            text = entry.toLine(showTimestamp),
                            color = entry.level.displayColor(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
                if (!atBottom) {
                    Surface(
                        onClick = { scope.launch { listState.animateScrollToItem(visible.lastIndex) } },
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shadowElevation = 4.dp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = "回到最新",
                                modifier = Modifier.size(18.dp),
                            )
                            if (unread > 0) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (unread > 99) "99+" else "$unread",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LogEntry.toLine(showTimestamp: Boolean): String = buildString {
    if (showTimestamp) append(formatLogTime(timeMillis)).append(' ')
    append(level.shortTag()).append(' ')
    append(message)
}
