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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.benton.izukijs.R
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.ui.common.EmptyState
import com.benton.izukijs.ui.common.formatDateTime
import com.benton.izukijs.ui.common.stableTopAppBarColors
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val filtered = remember(query, scripts) {
        if (query.isBlank()) scripts
        else scripts.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }

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
                val message = if (ok) {
                    context.getString(R.string.scripts_exported, target.name)
                } else {
                    context.getString(R.string.scripts_export_failed)
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(context, context.getString(R.string.scripts_imported, name), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.scripts_import_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scripts_title)) },
                scrollBehavior = scrollBehavior,
                colors = stableTopAppBarColors(),
                actions = {
                    IconButton(onClick = onOpenInspector) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.scripts_inspector))
                    }
                    Box {
                        IconButton(onClick = { showOverflow = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more))
                        }
                        DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.scripts_import)) },
                                onClick = {
                                    showOverflow = false
                                    importLauncher.launch(arrayOf("text/*", "application/javascript"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.scripts_schedule)) },
                                onClick = { showOverflow = false; onOpenSchedule() },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { newName = "script"; showNewDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.scripts_new))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (scripts.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.scripts_search)) },
                )
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when {
                    // 首次进入时列表尚在异步加载，先留空，避免闪一下「无匹配」空状态。
                    !loaded -> Unit

                    scripts.isEmpty() -> EmptyState(stringResource(R.string.scripts_empty))

                    filtered.isEmpty() -> EmptyState(stringResource(R.string.scripts_no_match, query.trim()))

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
            title = { Text(stringResource(R.string.scripts_new)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.scripts_name)) },
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
                }) { Text(stringResource(R.string.scripts_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.scripts_rename)) },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.scripts_name)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val renamed = container.scriptRepository.renameAsync(target, renameName)
                        container.scriptEnvRepository.move(target.id, renamed.id)
                        refresh()
                        renameTarget = null
                    }
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.scripts_delete_title)) },
            text = { Text(stringResource(R.string.scripts_delete_message, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.scriptRepository.deleteAsync(target)
                        container.scriptEnvRepository.remove(target.id)
                        deleteTarget = null
                        refresh()
                    }
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.common_cancel)) }
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
        supportingContent = { Text(formatDateTime(script.updatedAt)) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.scripts_rename)) },
                        onClick = { menuOpen = false; onRename() },
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
        },
    )
}

private fun writeTextToUri(context: Context, uri: Uri, text: String): Boolean = runCatching {
    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
    true
}.getOrDefault(false)

private fun readTextFromUri(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}.getOrNull()
