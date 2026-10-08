package com.benton.izukijs.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.model.EnvField
import com.benton.izukijs.model.ScriptEnvSpec
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.runtime.LogEntry
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.ScriptEnvDialog
import com.benton.izukijs.ui.common.rememberAppHaptics
import com.benton.izukijs.ui.console.ConsolePanel
import com.benton.izukijs.ui.rememberAppContainer
import kotlin.math.roundToInt
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(scriptId: String, onBack: () -> Unit) {
    val container = rememberAppContainer()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = LocalSnackbarController.current
    val haptics = rememberAppHaptics()

    var script by remember(scriptId) { mutableStateOf<ScriptInfo?>(null) }
    var editorValue by remember(scriptId) { mutableStateOf(TextFieldValue("")) }
    var savedCode by remember(scriptId) { mutableStateOf("") }
    var loaded by remember(scriptId) { mutableStateOf(false) }
    var consoleCollapsed by remember(scriptId) { mutableStateOf(true) }
    var envSource by remember(scriptId) { mutableStateOf<String?>(null) }
    var envFields by remember(scriptId) { mutableStateOf<List<EnvField>>(emptyList()) }
    var envInitial by remember(scriptId) { mutableStateOf<Map<String, String>>(emptyMap()) }

    // 查找 / 替换
    var findVisible by remember(scriptId) { mutableStateOf(false) }
    var query by remember(scriptId) { mutableStateOf("") }
    var replacement by remember(scriptId) { mutableStateOf("") }
    var activeMatch by remember(scriptId) { mutableStateOf(0) }

    // 撤销 / 重做历史。以脚本 id 为键，切脚本时清空。
    val undoStack = remember(scriptId) { ArrayDeque<EditorSnapshot>() }
    val redoStack = remember(scriptId) { ArrayDeque<EditorSnapshot>() }
    var canUndo by remember(scriptId) { mutableStateOf(false) }
    var canRedo by remember(scriptId) { mutableStateOf(false) }
    var lastEditAt by remember(scriptId) { mutableLongStateOf(0L) }

    val focusRequester = remember(scriptId) { FocusRequester() }

    val running by container.scriptExecutionManager.running.collectAsStateWithLifecycle()
    // 保留 State 而非解包：日志在子组件内读取，避免每次日志刷新都重组整个编辑器（含文本框排版）。
    val logsState = container.logBus.entries.collectAsStateWithLifecycle()
    val editorSettings by container.editorSettingsRepository.settings.collectAsStateWithLifecycle()
    val isDark = isSystemInDarkTheme()
    val highlighter = remember(isDark) {
        JsSyntaxHighlighter(
            if (isDark) JsSyntaxHighlighter.Palette.dark() else JsSyntaxHighlighter.Palette.light(),
        )
    }

    LaunchedEffect(scriptId) {
        val found = container.scriptRepository.findAsync(scriptId)
        val text = found?.let { container.scriptRepository.readAsync(it) } ?: ""
        script = found
        editorValue = TextFieldValue(text)
        savedCode = text
        undoStack.clear()
        redoStack.clear()
        canUndo = false
        canRedo = false
        loaded = true
    }

    if (!loaded) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.editor_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val current = script
    if (current == null) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text(stringResource(R.string.editor_not_found, scriptId), color = MaterialTheme.colorScheme.error)
        }
        return
    }

    val text = editorValue.text
    val dirty = text != savedCode

    val effectiveQuery = if (findVisible) query else ""
    val matches = remember(text, effectiveQuery) {
        if (effectiveQuery.isEmpty()) emptyList() else findAllMatches(text, effectiveQuery)
    }
    val activeIndex = if (matches.isEmpty()) -1 else activeMatch.coerceIn(0, matches.lastIndex)
    val activeRange = matches.getOrNull(activeIndex)
    LaunchedEffect(query) { activeMatch = 0 }

    val matchColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val activeMatchColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    val visualTransformation = remember(highlighter, effectiveQuery, activeRange, matchColor, activeMatchColor) {
        MatchHighlightTransformation(highlighter, effectiveQuery, matchColor, activeMatchColor, activeRange)
    }

    fun snapshot() = EditorSnapshot(editorValue.text, editorValue.selection)

    // 记录一次编辑：短时间内连续输入合并为一步，避免每个按键都占一格历史。
    fun recordEdit(previous: EditorSnapshot, force: Boolean) {
        val now = System.currentTimeMillis()
        val coalesce = !force && now - lastEditAt < HISTORY_COALESCE_MS && undoStack.isNotEmpty()
        if (!coalesce) {
            undoStack.addLast(previous)
            while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        }
        redoStack.clear()
        lastEditAt = now
        canUndo = undoStack.isNotEmpty()
        canRedo = false
    }

    // 程序化编辑（替换等），始终单独占一格历史；并重置合并窗口，
    // 避免紧随其后的输入被并入同一步撤销。
    fun applyEdit(newText: String, newSelection: TextRange) {
        if (newText == editorValue.text) return
        recordEdit(snapshot(), force = true)
        lastEditAt = 0L
        editorValue = TextFieldValue(newText, newSelection)
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(snapshot())
        editorValue = TextFieldValue(previous.text, previous.selection)
        lastEditAt = 0L
        canUndo = undoStack.isNotEmpty()
        canRedo = true
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(snapshot())
        editorValue = TextFieldValue(next.text, next.selection)
        lastEditAt = 0L
        canUndo = true
        canRedo = redoStack.isNotEmpty()
    }

    fun focusMatch(index: Int) {
        val range = matches.getOrNull(index) ?: return
        activeMatch = index
        editorValue = editorValue.copy(selection = TextRange(range.first, range.last + 1))
        focusRequester.requestFocus()
    }

    fun findNext() {
        if (matches.isEmpty()) return
        focusMatch(if (activeIndex < 0) 0 else (activeIndex + 1) % matches.size)
    }

    fun findPrev() {
        if (matches.isEmpty()) return
        focusMatch(if (activeIndex < 0) matches.lastIndex else (activeIndex - 1 + matches.size) % matches.size)
    }

    fun replaceCurrent() {
        val range = matches.getOrNull(activeIndex) ?: return
        val newText = text.replaceRange(range.first, range.last + 1, replacement)
        applyEdit(newText, TextRange(range.first + replacement.length))
    }

    fun replaceAllMatches() {
        if (effectiveQuery.isEmpty()) return
        val newText = text.replace(effectiveQuery, replacement)
        if (newText == text) return
        applyEdit(newText, TextRange(editorValue.selection.start.coerceAtMost(newText.length)))
    }

    fun handleShortcut(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return false
        return when (event.key) {
            Key.Z -> {
                if (event.isShiftPressed) redo() else undo()
                true
            }

            Key.Y -> {
                redo()
                true
            }

            Key.F -> {
                findVisible = true
                true
            }

            else -> false
        }
    }

    // 自动保存：停止输入片刻后静默落盘，避免只依赖返回时写盘导致丢失。
    // 写盘用 NonCancellable 包裹，防止下一次按键取消协程时写到一半被中断。
    LaunchedEffect(text, editorSettings.autoSave) {
        if (!editorSettings.autoSave) return@LaunchedEffect
        if (text == savedCode) return@LaunchedEffect
        delay(AUTOSAVE_DELAY_MS)
        val cached = text
        withContext(NonCancellable) { container.scriptRepository.writeAsync(current, cached) }
        savedCode = cached
    }

    // 保存与后续动作都在后台线程执行，完成后再触发导航，避免主线程写盘也能保证落盘。
    fun saveAndThen(action: () -> Unit) {
        val snapshot = editorValue.text
        scope.launch {
            withContext(NonCancellable) { container.scriptRepository.writeAsync(current, snapshot) }
            savedCode = snapshot
            action()
        }
    }

    BackHandler { saveAndThen(onBack) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            current.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (dirty) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.editor_unsaved),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { saveAndThen(onBack) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { undo() }, enabled = canUndo) {
                        Icon(
                            ImageVector.vectorResource(R.drawable.ic_undo),
                            contentDescription = stringResource(R.string.editor_undo),
                        )
                    }
                    IconButton(onClick = { redo() }, enabled = canRedo) {
                        Icon(
                            ImageVector.vectorResource(R.drawable.ic_redo),
                            contentDescription = stringResource(R.string.editor_redo),
                        )
                    }
                    IconButton(onClick = { findVisible = !findVisible }) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(R.string.editor_find),
                        )
                    }
                    if (running) {
                        IconButton(onClick = {
                            haptics.reject()
                            container.scriptExecutionManager.requestStop()
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_stop))
                        }
                    } else {
                        IconButton(onClick = {
                            haptics.tap()
                            val fullText = editorValue.text
                            val selection = editorValue.selection
                            val isSelection = !selection.collapsed
                            val runText = if (isSelection) {
                                fullText.substring(selection.min, selection.max)
                            } else {
                                fullText
                            }
                            scope.launch {
                                withContext(NonCancellable) {
                                    container.scriptRepository.writeAsync(current, fullText)
                                }
                                savedCode = fullText
                                if (isSelection) {
                                    snackbar.show(context.getString(R.string.editor_run_selection))
                                }
                                val spec = ScriptEnvSpec.parse(runText)
                                val stored = container.scriptEnvRepository.values(current.id)
                                if (spec.isEmpty && stored.isEmpty()) {
                                    container.scriptExecutionManager.run(current.name, runText)
                                } else {
                                    envFields = spec.fields
                                    envInitial = spec.defaults() + stored
                                    envSource = runText
                                }
                            }
                        }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.common_run))
                        }
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            val density = LocalDensity.current
            val minExpanded = 140.dp
            val maxExpanded = (maxHeight * 0.8f).coerceAtLeast(minExpanded)
            var consoleHeight by remember(scriptId) { mutableStateOf(editorSettings.consoleHeightDp.dp) }
            // 设置里改动控制台高度、或配置异步载入后，同步一次；拖动保存回同值时无副作用。
            LaunchedEffect(editorSettings.consoleHeightDp) {
                consoleHeight = editorSettings.consoleHeightDp.dp
            }
            val drawerHeight = consoleHeight.coerceIn(minExpanded, maxExpanded)

            Column(modifier = Modifier.fillMaxSize()) {
                if (findVisible) {
                    FindReplacePanel(
                        query = query,
                        onQueryChange = { query = it },
                        replacement = replacement,
                        onReplacementChange = { replacement = it },
                        matchCount = matches.size,
                        activePosition = if (activeIndex >= 0) activeIndex + 1 else 0,
                        onPrev = { findPrev() },
                        onNext = { findNext() },
                        onReplace = { replaceCurrent() },
                        onReplaceAll = { replaceAllMatches() },
                        onClose = {
                            findVisible = false
                            focusRequester.requestFocus()
                        },
                    )
                }

                val baseStyle = LocalTextStyle.current
                val editorColor = MaterialTheme.colorScheme.onSurface
                val editorStyle = remember(baseStyle, editorSettings.fontSizeSp, editorColor) {
                    baseStyle.copy(
                        color = editorColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = editorSettings.fontSizeSp.sp,
                        lineHeight = (editorSettings.fontSizeSp * LINE_HEIGHT_RATIO).sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(8.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                ) {
                    BasicTextField(
                        value = editorValue,
                        onValueChange = { newValue ->
                            if (newValue.text != editorValue.text) {
                                recordEdit(snapshot(), force = false)
                            }
                            editorValue = newValue
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { event -> handleShortcut(event) },
                        textStyle = editorStyle,
                        visualTransformation = visualTransformation,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            if (text.isEmpty()) {
                                Text(
                                    stringResource(R.string.editor_placeholder),
                                    style = editorStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        },
                    )
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { dy ->
                                val dyDp = with(density) { dy.toDp() }
                                consoleHeight = (consoleHeight - dyDp).coerceIn(minExpanded, maxExpanded)
                                if (consoleCollapsed && dy < 0f) consoleCollapsed = false
                            },
                            onDragStopped = {
                                val clamped = consoleHeight.coerceIn(minExpanded, maxExpanded)
                                consoleHeight = clamped
                                container.editorSettingsRepository.save(
                                    editorSettings.copy(consoleHeightDp = clamped.value.roundToInt()),
                                )
                            },
                        )
                        .clickable {
                            if (consoleCollapsed) {
                                consoleHeight = consoleHeight.coerceAtLeast(minExpanded)
                                consoleCollapsed = false
                            } else {
                                consoleCollapsed = true
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.editor_console), style = MaterialTheme.typography.titleSmall)
                    LogCountBadge(logsState)
                    Spacer(Modifier.weight(1f))
                    Icon(
                        if (consoleCollapsed) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(
                            if (consoleCollapsed) R.string.editor_expand_console else R.string.editor_collapse_console,
                        ),
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (!consoleCollapsed) {
                    EditorConsolePanel(
                        logsState = logsState,
                        onClear = { container.logBus.clear() },
                        modifier = Modifier.fillMaxWidth().height(drawerHeight),
                    )
                }
            }
        }
    }

    envSource?.let { source ->
        ScriptEnvDialog(
            scriptName = current.name,
            fields = envFields,
            initial = envInitial,
            onDismiss = { envSource = null },
            onConfirm = { values ->
                container.scriptEnvRepository.saveValues(current.id, values)
                container.scriptExecutionManager.run(current.name, source, values)
                envSource = null
            },
        )
    }
}

private const val AUTOSAVE_DELAY_MS = 800L
private const val LINE_HEIGHT_RATIO = 1.4f

/** 撤销历史的合并窗口：此时间内的连续输入合并为一步。 */
private const val HISTORY_COALESCE_MS = 500L

/** 撤销栈上限，避免长时间编辑占用过多内存。 */
private const val MAX_HISTORY = 200

/** 一次可撤销的编辑快照。 */
private data class EditorSnapshot(val text: String, val selection: TextRange)

@Composable
private fun FindReplacePanel(
    query: String,
    onQueryChange: (String) -> Unit,
    replacement: String,
    onReplacementChange: (String) -> Unit,
    matchCount: Int,
    activePosition: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 3.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.editor_find)) },
                    placeholder = { Text(stringResource(R.string.editor_find_placeholder)) },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (matchCount == 0) {
                        stringResource(R.string.editor_match_none)
                    } else {
                        stringResource(R.string.editor_match_of, activePosition, matchCount)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onPrev, enabled = matchCount > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.editor_prev_match))
                }
                IconButton(onClick = onNext, enabled = matchCount > 0) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.editor_next_match))
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.editor_close_find))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = replacement,
                    onValueChange = onReplacementChange,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.editor_replace)) },
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onReplace, enabled = matchCount > 0) {
                    Text(stringResource(R.string.editor_replace_one))
                }
                TextButton(onClick = onReplaceAll, enabled = matchCount > 0) {
                    Text(stringResource(R.string.editor_replace_all))
                }
            }
        }
    }
}

/** 单独读取日志条数，使日志刷新只重组这个徽标，不牵连编辑器本体。 */
@Composable
private fun LogCountBadge(logsState: State<List<LogEntry>>) {
    val count = logsState.value.size
    if (count > 0) {
        Spacer(Modifier.width(6.dp))
        Text(
            "$count",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 在子作用域内解包日志列表，同样把重组范围限制在控制台面板内。 */
@Composable
private fun EditorConsolePanel(
    logsState: State<List<LogEntry>>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ConsolePanel(
        entries = logsState.value,
        onClear = onClear,
        showHeader = false,
        modifier = modifier,
    )
}
