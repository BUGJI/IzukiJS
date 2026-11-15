package com.benton.izukijs.ui.console

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.benton.izukijs.R
import com.benton.izukijs.core.log.LogEntry
import com.benton.izukijs.core.log.LogLevel
import com.benton.izukijs.core.perf.PerfMetrics
import com.benton.izukijs.ui.common.ConfirmDialog
import com.benton.izukijs.ui.common.EmptyState
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.displayColor
import com.benton.izukijs.ui.common.formatLogTime
import com.benton.izukijs.ui.common.localizedName
import com.benton.izukijs.ui.common.rememberAppHaptics
import com.benton.izukijs.ui.common.shortTag
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarController.current
    val haptics = rememberAppHaptics()
    val listState = rememberLazyListState()
    var enabledLevelNames by rememberSaveable {
        mutableStateOf(LogLevel.entries.map { it.name })
    }
    val enabledLevels = remember(enabledLevelNames) {
        enabledLevelNames.mapNotNull { name -> LogLevel.entries.firstOrNull { it.name == name } }.toSet()
    }
    var query by rememberSaveable { mutableStateOf("") }
    var wrapLines by rememberSaveable { mutableStateOf(true) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var confirmClear by remember { mutableStateOf(false) }
    var levelMenuOpen by remember { mutableStateOf(false) }
    var actionsMenuOpen by remember { mutableStateOf(false) }

    val copyLabel = stringResource(R.string.common_copy)
    val trimmedQuery = query.trim()
    val visible = remember(entries, enabledLevels, trimmedQuery) {
        PerfMetrics.measure("console.filter") {
            entries.filter { entry ->
                entry.level in enabledLevels &&
                    (trimmedQuery.isEmpty() || entry.message.contains(trimmedQuery, ignoreCase = true))
            }
        }
    }

    // 仅在用户本来就停在底部时跟随；避免打断向上翻阅，也避免 animate 在高速输出下被反复重启。
    // 「跟随」关闭（暂停）时完全停止自动滚动。用最后一条 id 作 key，覆盖「数量不变但内容被替换」的情况。
    LaunchedEffect(visible.lastOrNull()?.id, follow) {
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
        haptics.tap()
        val text = visible.joinToString("\n") { it.toLine(showTimestamp) }
        clipboard()?.setPrimaryClip(ClipData.newPlainText(resources.getString(R.string.logs_name), text))
        scope.launch { snackbar.show(resources.getString(R.string.console_copied_results)) }
    }

    fun copyLine(entry: LogEntry) {
        haptics.tap()
        clipboard()?.setPrimaryClip(
            ClipData.newPlainText(resources.getString(R.string.logs_name), entry.toLine(showTimestamp)),
        )
        scope.launch { snackbar.show(resources.getString(R.string.console_copied)) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (showHeader) {
            Text(
                stringResource(R.string.editor_console),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp),
            )
        }
        // 工具栏压到一行：搜索常显，级别 / 换行 / 跟随 / 更多收成图标，复制与清空进入「更多」。
        // 本地触控下限降到 40dp，换取窄屏下搜索框不被四个 48dp 图标挤没。
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showFilter) {
                    ConsoleSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(4.dp))
                    Box {
                        IconButton(onClick = { levelMenuOpen = true }) {
                            Icon(
                                Icons.Filled.FilterList,
                                contentDescription = stringResource(R.string.console_level_filter),
                                tint = if (enabledLevels.size != LogLevel.entries.size) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = levelMenuOpen,
                            onDismissRequest = { levelMenuOpen = false },
                        ) {
                            LogLevel.entries.forEach { level ->
                                DropdownMenuItem(
                                    text = { Text(level.localizedName()) },
                                    trailingIcon = {
                                        if (level in enabledLevels) {
                                            Icon(Icons.Filled.Check, contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        enabledLevelNames = if (level in enabledLevels) {
                                            enabledLevelNames - level.name
                                        } else {
                                            enabledLevelNames + level.name
                                        }
                                    },
                                )
                            }
                        }
                    }
                    IconToggleButton(checked = wrapLines, onCheckedChange = { wrapLines = it }) {
                        Icon(
                            Icons.AutoMirrored.Filled.WrapText,
                            contentDescription = stringResource(R.string.console_wrap),
                            tint = if (wrapLines) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconToggleButton(
                        checked = follow,
                        onCheckedChange = { next ->
                            follow = next
                            if (next) {
                                scope.launch {
                                    listState.animateScrollToItem(visible.lastIndex.coerceAtLeast(0))
                                }
                            }
                        },
                    ) {
                        Icon(
                            Icons.Filled.VerticalAlignBottom,
                            contentDescription = stringResource(R.string.console_follow),
                            tint = if (follow) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Box {
                    IconButton(onClick = { actionsMenuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.common_more),
                        )
                    }
                    DropdownMenu(
                        expanded = actionsMenuOpen,
                        onDismissRequest = { actionsMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.console_copy_results)) },
                            enabled = visible.isNotEmpty(),
                            onClick = {
                                actionsMenuOpen = false
                                copyAll()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_clear)) },
                            enabled = entries.isNotEmpty(),
                            onClick = {
                                actionsMenuOpen = false
                                confirmClear = true
                            },
                        )
                        if (onCollapse != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_collapse_console)) },
                                onClick = {
                                    actionsMenuOpen = false
                                    onCollapse()
                                },
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        when {
            entries.isEmpty() -> EmptyState(stringResource(R.string.console_empty))
            visible.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.console_filtered_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    query = ""
                    enabledLevelNames = LogLevel.entries.map { it.name }
                }) { Text(stringResource(R.string.console_reset_filter)) }
            }
            else -> Box(modifier = Modifier.fillMaxSize()) {
                // 日志行整体压到 32dp：默认 48dp 的触控下限会把一屏日志挤到 6~8 行。
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        items(visible, key = { it.id }) { entry ->
                            // 时间与消息分开着色后只在该条 / 时间戳开关变化时重建 AnnotatedString。
                            val levelColor = entry.level.displayColor()
                            val timeColor = MaterialTheme.colorScheme.onSurfaceVariant
                            val messageColor = MaterialTheme.colorScheme.onSurface
                            val line = remember(entry, showTimestamp, levelColor, timeColor, messageColor) {
                                entry.toAnnotatedLine(showTimestamp, levelColor, timeColor, messageColor)
                            }
                            // 关闭换行时不再用省略号截断，改为单行横向滚动查看完整内容；
                            // 换行模式不创建无用的 ScrollState。
                            val rowModifier = if (wrapLines) {
                                Modifier.fillMaxWidth()
                            } else {
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            }
                            Box(
                                modifier = rowModifier
                                    .minimumInteractiveComponentSize()
                                    .clickable(onClickLabel = copyLabel) { copyLine(entry) },
                            ) {
                                Text(
                                    text = line,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    softWrap = wrapLines,
                                    maxLines = if (wrapLines) Int.MAX_VALUE else 1,
                                    overflow = TextOverflow.Clip,
                                    modifier = Modifier.then(if (wrapLines) Modifier.fillMaxWidth() else Modifier),
                                )
                            }
                        }
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

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.common_clear),
            message = stringResource(R.string.console_clear_confirm),
            destructive = true,
            onDismiss = { confirmClear = false },
            onConfirm = {
                haptics.reject()
                onClear()
                confirmClear = false
            },
        )
    }
}

/** 紧凑搜索框：避免 OutlinedTextField 的 56dp 最小高度把工具栏撑成两行。 */
@Composable
private fun ConsoleSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    Surface(
        // 44dp 给 2x 字体留出余量：40dp 时单行 bodyMedium 可能裁掉下缘。
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                stringResource(R.string.console_search),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.console_clear_search),
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

private fun LogEntry.toAnnotatedLine(
    showTimestamp: Boolean,
    levelColor: Color,
    timeColor: Color,
    messageColor: Color,
): AnnotatedString = buildAnnotatedString {
    if (showTimestamp) {
        withStyle(SpanStyle(color = timeColor)) { append(formatLogTime(timeMillis)) }
        append(' ')
    }
    withStyle(SpanStyle(color = levelColor, fontWeight = FontWeight.Medium)) {
        append(level.shortTag())
    }
    append(' ')
    withStyle(SpanStyle(color = messageColor)) { append(message) }
}

private fun LogEntry.toLine(showTimestamp: Boolean): String = buildString {
    if (showTimestamp) append(formatLogTime(timeMillis)).append(' ')
    append(level.shortTag()).append(' ')
    append(message)
}
