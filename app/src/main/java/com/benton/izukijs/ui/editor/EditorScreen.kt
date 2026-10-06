package com.benton.izukijs.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.model.EnvField
import com.benton.izukijs.model.ScriptEnvSpec
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.ui.common.ScriptEnvDialog
import com.benton.izukijs.ui.console.ConsolePanel
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(scriptId: String, onBack: () -> Unit) {
    val container = rememberAppContainer()
    val scope = rememberCoroutineScope()

    var script by remember(scriptId) { mutableStateOf<ScriptInfo?>(null) }
    var code by remember(scriptId) { mutableStateOf("") }
    var savedCode by remember(scriptId) { mutableStateOf("") }
    var loaded by remember(scriptId) { mutableStateOf(false) }
    var consoleCollapsed by remember(scriptId) { mutableStateOf(true) }
    var envSource by remember(scriptId) { mutableStateOf<String?>(null) }
    var envFields by remember(scriptId) { mutableStateOf<List<EnvField>>(emptyList()) }
    var envInitial by remember(scriptId) { mutableStateOf<Map<String, String>>(emptyMap()) }

    val running by container.scriptExecutionManager.running.collectAsStateWithLifecycle()
    val logs by container.logBus.entries.collectAsStateWithLifecycle()
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
        code = text
        savedCode = text
        loaded = true
    }

    if (!loaded) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("加载中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val current = script
    if (current == null) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("脚本不存在: $scriptId", color = MaterialTheme.colorScheme.error)
        }
        return
    }

    val dirty = code != savedCode

    // 自动保存：停止输入片刻后静默落盘，避免只依赖返回时写盘导致丢失。
    // 写盘用 NonCancellable 包裹，防止下一次按键取消协程时写到一半被中断。
    LaunchedEffect(code, editorSettings.autoSave) {
        if (!editorSettings.autoSave) return@LaunchedEffect
        if (code == savedCode) return@LaunchedEffect
        delay(AUTOSAVE_DELAY_MS)
        val snapshot = code
        withContext(NonCancellable) { container.scriptRepository.writeAsync(current, snapshot) }
        savedCode = snapshot
    }

    // 保存与后续动作都在后台线程执行，完成后再触发导航，避免主线程写盘也能保证落盘。
    fun saveAndThen(action: () -> Unit) {
        val snapshot = code
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
                                "未保存",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { saveAndThen(onBack) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (running) {
                        IconButton(onClick = { container.scriptExecutionManager.requestStop() }) {
                            Icon(Icons.Filled.Close, contentDescription = "停止")
                        }
                    } else {
                        IconButton(onClick = {
                            val snapshot = code
                            scope.launch {
                                withContext(NonCancellable) {
                                    container.scriptRepository.writeAsync(current, snapshot)
                                }
                                savedCode = snapshot
                                val spec = ScriptEnvSpec.parse(snapshot)
                                val stored = container.scriptEnvRepository.values(current.id)
                                if (spec.isEmpty && stored.isEmpty()) {
                                    container.scriptExecutionManager.run(current.name, snapshot)
                                } else {
                                    envFields = spec.fields
                                    envInitial = spec.defaults() + stored
                                    envSource = snapshot
                                }
                            }
                        }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "运行")
                        }
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            val density = LocalDensity.current
            val minExpanded = 140.dp
            val maxExpanded = (maxHeight * 0.8f).coerceAtLeast(minExpanded)
            var consoleHeight by remember(scriptId) { mutableStateOf(240.dp) }
            val drawerHeight = consoleHeight.coerceIn(minExpanded, maxExpanded)

            Column(modifier = Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = editorSettings.fontSizeSp.sp,
                        lineHeight = (editorSettings.fontSizeSp * LINE_HEIGHT_RATIO).sp,
                    ),
                    visualTransformation = highlighter,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    placeholder = { Text("// 在此编写 JS 脚本") },
                )
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
                    Text("控制台", style = MaterialTheme.typography.titleSmall)
                    if (logs.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${logs.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(
                        if (consoleCollapsed) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (consoleCollapsed) "展开控制台" else "收起控制台",
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (!consoleCollapsed) {
                    ConsolePanel(
                        entries = logs,
                        onClear = { container.logBus.clear() },
                        showHeader = false,
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
