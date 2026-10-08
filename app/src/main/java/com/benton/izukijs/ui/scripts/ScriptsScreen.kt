package com.benton.izukijs.ui.scripts

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.data.ScriptSort
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.ui.common.EmptyState
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.formatDateTime
import com.benton.izukijs.ui.common.rememberAppHaptics
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
    val snackbar = LocalSnackbarController.current
    val haptics = rememberAppHaptics()
    val pinned by container.scriptListPreferences.pinned.collectAsStateWithLifecycle()
    val sort by container.scriptListPreferences.sort.collectAsStateWithLifecycle()

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

    // 置顶优先，其次按所选排序方式。
    val filtered = remember(query, scripts, pinned, sort) {
        val bySort = when (sort) {
            ScriptSort.UPDATED -> compareByDescending<ScriptInfo> { it.updatedAt }
            ScriptSort.NAME -> compareBy { it.name.lowercase() }
        }
        val ordered = scripts.sortedWith(compareByDescending<ScriptInfo> { it.id in pinned }.then(bySort))
        if (query.isBlank()) ordered
        else ordered.filter { it.name.contains(query.trim(), ignoreCase = true) }
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
                snackbar.show(message)
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
                    snackbar.show(context.getString(R.string.scripts_imported, name))
                } else {
                    snackbar.show(context.getString(R.string.scripts_import_failed))
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
                            HorizontalDivider()
                            SortMenuItem(
                                label = stringResource(R.string.scripts_sort_updated),
                                selected = sort == ScriptSort.UPDATED,
                                onClick = {
                                    container.scriptListPreferences.setSort(ScriptSort.UPDATED)
                                    showOverflow = false
                                },
                            )
                            SortMenuItem(
                                label = stringResource(R.string.scripts_sort_name),
                                selected = sort == ScriptSort.NAME,
                                onClick = {
                                    container.scriptListPreferences.setSort(ScriptSort.NAME)
                                    showOverflow = false
                                },
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
                    // 首次进入时用骨架屏占位，避免闪一下空状态。
                    !loaded -> ScriptsSkeleton()

                    scripts.isEmpty() -> EmptyState(stringResource(R.string.scripts_empty))

                    filtered.isEmpty() -> EmptyState(stringResource(R.string.scripts_no_match, query.trim()))

                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filtered, key = { it.id }) { script ->
                            ScriptRow(
                                script = script,
                                pinned = script.id in pinned,
                                onClick = { onOpenScript(script.id) },
                                onTogglePin = {
                                    haptics.toggle()
                                    container.scriptListPreferences.togglePinned(script.id)
                                },
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
                    haptics.reject()
                    deleteTarget = null
                    scope.launch {
                        // 删除前留档，供 Snackbar 的「撤销」恢复。
                        val content = container.scriptRepository.readAsync(target)
                        val values = container.scriptEnvRepository.values(target.id)
                        val state = container.scriptEnvRepository.state(target.id)
                        container.scriptRepository.deleteAsync(target)
                        container.scriptEnvRepository.remove(target.id)
                        refresh()
                        val result = snackbar.show(
                            message = context.getString(R.string.scripts_deleted, target.name),
                            actionLabel = context.getString(R.string.common_undo),
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            val restored = container.scriptRepository.createAsync(target.name, content)
                            if (values.isNotEmpty()) container.scriptEnvRepository.saveValues(restored.id, values)
                            state.forEach { (key, value) ->
                                container.scriptEnvRepository.putState(restored.id, key, value)
                            }
                            refresh()
                        }
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
    pinned: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    ListItem(
        leadingContent = {
            IconButton(onClick = onTogglePin) {
                Icon(
                    ImageVector.vectorResource(if (pinned) R.drawable.ic_pin else R.drawable.ic_pin_outline),
                    contentDescription = stringResource(
                        if (pinned) R.string.scripts_unpin else R.string.scripts_pin,
                    ),
                    tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
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
                        text = { Text(stringResource(if (pinned) R.string.scripts_unpin else R.string.scripts_pin)) },
                        onClick = { menuOpen = false; onTogglePin() },
                    )
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

@Composable
private fun SortMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
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
private fun ScriptsSkeleton() {
    Column(modifier = Modifier.fillMaxSize()) {
        repeat(5) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShimmerBox(width = 20.dp, height = 20.dp)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    ShimmerBox(width = 140.dp, height = 16.dp)
                    Spacer(Modifier.height(8.dp))
                    ShimmerBox(width = 88.dp, height = 12.dp)
                }
                Spacer(Modifier.width(16.dp))
                ShimmerBox(width = 20.dp, height = 20.dp)
            }
        }
    }
}

@Composable
private fun ShimmerBox(width: Dp, height: Dp) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.5f),
                RoundedCornerShape(4.dp),
            ),
    )
}

private fun writeTextToUri(context: Context, uri: Uri, text: String): Boolean = runCatching {
    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
    true
}.getOrDefault(false)

private fun readTextFromUri(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}.getOrNull()
