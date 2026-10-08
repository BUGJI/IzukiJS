package com.benton.izukijs.ui.console

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.benton.izukijs.R
import com.benton.izukijs.runtime.LogEntry
import com.benton.izukijs.runtime.LogLevel
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.EmptyState
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.displayColor
import com.benton.izukijs.ui.common.formatLogTime
import com.benton.izukijs.ui.common.localizedName
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
    val snackbar = LocalSnackbarController.current
    val listState = rememberLazyListState()
    var enabledLevels by remember { mutableStateOf(LogLevel.entries.toSet()) }
    var query by remember { mutableStateOf("") }
    var wrapLines by remember { mutableStateOf(true) }
    var follow by remember { mutableStateOf(true) }

    val trimmedQuery = query.trim()
    val visible = remember(entries, enabledLevels, trimmedQuery) {
        entries.filter { entry ->
            entry.level in enabledLevels &&
                (trimmedQuery.isEmpty() || entry.message.contains(trimmedQuery, ignoreCase = true))
        }
    }

    // 仅在用户本来就停在底部时跟随；避免打断向上翻阅，也避免 animate 在高速输出下被反复重启。
    // 「跟随」关闭（暂停）时完全停止自动滚动。
    LaunchedEffect(visible.size, follow) {
        if (follow && visible.isNotEmpty() && !listState.canScrollForward) {
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

    fun clipboard(): ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    fun copyAll() {
        if (visible.isEmpty()) return
        val text = visible.joinToString("\n") { it.toLine(showTimestamp) }
        clipboard()?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.nav_logs), text))
        scope.launch { snackbar.show(context.getString(R.string.console_copied_all)) }
    }

    fun copyLine(entry: LogEntry) {
        clipboard()?.setPrimaryClip(
            ClipData.newPlainText(context.getString(R.string.nav_logs), entry.toLine(showTimestamp)),
        )
        scope.launch { snackbar.show(context.getString(R.string.console_copied)) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showHeader) {
                Text(stringResource(R.string.editor_console), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { copyAll() }, enabled = visible.isNotEmpty()) {
                Text(stringResource(R.string.common_copy))
            }
            TextButton(onClick = onClear) { Text(stringResource(R.string.common_clear)) }
            if (onCollapse != null) {
                IconButton(onClick = onCollapse) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.editor_collapse_console),
                    )
                }
            }
        }

        if (showFilter) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.console_clear_search),
                            )
                        }
                    }
                },
                placeholder = { Text(stringResource(R.string.console_search)) },
            )
            ChipFlow(modifier = Modifier.padding(horizontal = 12.dp)) {
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
                        label = { Text(level.localizedName()) },
                    )
                }
                FilterChip(
                    selected = wrapLines,
                    onClick = { wrapLines = !wrapLines },
                    label = { Text(stringResource(R.string.console_wrap)) },
                )
                FilterChip(
                    selected = follow,
                    onClick = {
                        val next = !follow
                        follow = next
                        if (next) scope.launch { listState.animateScrollToItem(visible.lastIndex.coerceAtLeast(0)) }
                    },
                    label = { Text(stringResource(R.string.console_follow)) },
                )
            }
        }

        HorizontalDivider()
        when {
            entries.isEmpty() -> EmptyState(stringResource(R.string.console_empty))
            visible.isEmpty() -> EmptyState(stringResource(R.string.console_filtered_empty))
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
                            softWrap = wrapLines,
                            maxLines = if (wrapLines) Int.MAX_VALUE else 1,
                            overflow = if (wrapLines) TextOverflow.Clip else TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { copyLine(entry) },
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
                                contentDescription = stringResource(R.string.console_back_to_latest),
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
