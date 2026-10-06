package com.benton.izukijs.ui.scripts

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreen(
    onOpenScript: (String) -> Unit,
    onOpenInspector: () -> Unit,
    onOpenSchedule: () -> Unit,
) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var scripts by remember { mutableStateOf<List<ScriptInfo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var showNewDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("script") }
    var showOverflow by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ScriptInfo?>(null) }
    var renameName by remember { mutableStateOf("") }
    var exportTarget by remember { mutableStateOf<ScriptInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<ScriptInfo?>(null) }
    var query by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            scripts = container.scriptRepository.listAsync()
            loaded = true
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/javascript"),
    ) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri != null && target != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    writeTextToUri(context, uri, container.scriptRepository.read(target))
                }
                Toast.makeText(context, if (ok) "已导出 ${target.name}" else "导出失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) { readTextFromUri(context, uri) }
                if (text != null) {
                    val name = uri.lastPathSegment
                        ?.substringAfterLast('/')
                        ?.substringBeforeLast('.')
                        .orEmpty()
                        .ifBlank { "imported" }
                    container.scriptRepository.createAsync(name, text)
                    refresh()
                    Toast.makeText(context, "已导入 $name", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "导入失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("脚本中心") },
                actions = {
                    IconButton(onClick = onOpenInspector) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "布局分析")
                    }
                    Box {
                        IconButton(onClick = { showOverflow = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                            DropdownMenuItem(
                                text = { Text("导入脚本") },
                                onClick = {
                                    showOverflow = false
                                    importLauncher.launch(arrayOf("text/*", "application/javascript"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("定时任务") },
                                onClick = { showOverflow = false; onOpenSchedule() },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { newName = "script"; showNewDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建脚本")
            }
        },
    ) { padding ->
        val filtered = if (query.isBlank()) {
            scripts
        } else {
            scripts.filter { it.name.contains(query.trim(), ignoreCase = true) }
        }
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (scripts.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text("搜索脚本") },
                )
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when {
                    loaded && scripts.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("还没有脚本，点击右下角新建", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    filtered.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "没有匹配「${query.trim()}」的脚本",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filtered, key = { it.id }) { script ->
                            ScriptRow(
                                script = script,
                                onClick = { onOpenScript(script.id) },
                                onRename = { renameTarget = script; renameName = script.name },
                                onExport = { exportTarget = script; exportLauncher.launch(script.name + ".js") },
                                onDelete = { deleteTarget = script },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text("新建脚本") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("名称") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val created = container.scriptRepository.createAsync(newName)
                        refresh()
                        showNewDialog = false
                        onOpenScript(created.id)
                    }
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false }) { Text("取消") }
            },
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    singleLine = true,
                    label = { Text("名称") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.scriptRepository.renameAsync(target, renameName)
                        refresh()
                        renameTarget = null
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除脚本") },
            text = { Text("确定删除「${target.name}」吗？此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.scriptRepository.deleteAsync(target)
                        deleteTarget = null
                        refresh()
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ScriptRow(
    script: ScriptInfo,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(script.name) },
        supportingContent = { Text(formatTime(script.updatedAt)) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("导出") },
                        onClick = { menuOpen = false; onExport() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        },
    )
}

private val TIME_FORMATTER = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatTime(millis: Long): String = TIME_FORMATTER.format(Date(millis))

private fun writeTextToUri(context: Context, uri: Uri, text: String): Boolean = runCatching {
    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
    true
}.getOrDefault(false)

private fun readTextFromUri(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}.getOrNull()
