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
import androidx.compose.material.icons.filled.MoreVert
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
import com.benton.izukijs.ui.common.ConfirmDialog
import com.benton.izukijs.ui.common.EmptyState
import com.benton.izukijs.ui.common.IzukiPageScaffold
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.PageMaxWidth
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
fun ScriptsScreen(
    onOpenScript: (String) -> Unit,
    onEditScript: (String) -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenFlows: () -> Unit,
) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarController.current
    val haptics = rememberAppHaptics()
    val deletion = remember(container) { ScriptDeletion(container) }
    val defaultName = stringResource(R.string.scripts_default_name)
    val pinned by container.scriptListPreferences.pinned.collectAsStateWithLifecycle()
    val tags by container.scriptListPreferences.tags.collectAsStateWithLifecycle()
    val sort by container.scriptListPreferences.sort.collectAsStateWithLifecycle()
    val groupMode by container.scriptListPreferences.groupMode.collectAsStateWithLifecycle()
    val tileScriptId by container.tileSettingsRepository.scriptId.collectAsStateWithLifecycle()
    val revision by container.scriptRepository.revision.collectAsStateWithLifecycle()
    val subscriptionRevision by container.subscriptionRepository.revision.collectAsStateWithLifecycle()

    var scripts by remember { mutableStateOf<List<ScriptInfo>>(emptyList()) }
    var subscriptions by remember { mutableStateOf<Map<String, ScriptSubscription>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf(false) }
    var showNewDialog by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var showOverflow by remember { mutableStateOf(false) }
    var exportTarget by remember { mutableStateOf<ScriptInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<ScriptInfo?>(null) }
    var tagTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    val tagTarget = scripts.firstOrNull { it.id == tagTargetId }
    var tagInput by rememberSaveable { mutableStateOf("") }
    var pendingScanFile by remember { mutableStateOf<File?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    // 多选只存 id 列表，rememberSaveable 可直接序列化；是否处于选择模式由非空推导。
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val selectedIdSet = remember(selectedIds) { selectedIds.toSet() }
    val selectionMode = selectedIds.isNotEmpty()
    var showBatchTagDialog by remember { mutableStateOf(false) }
    var batchTagMode by rememberSaveable { mutableStateOf(BATCH_TAG_ADD) }
    var batchTagInput by rememberSaveable { mutableStateOf("") }
    var batchDeletePending by remember { mutableStateOf(false) }
    // 只保存 id：SAF 选择器打开期间若页面重建，仍能按 id 找回待导出脚本。
    var batchExportIds by rememberSaveable { mutableStateOf(emptyList<String>()) }

    // 置顶优先，其次按所选排序方式；搜索只在已排序结果上过滤。
    val ordered = remember(scripts, pinned, sort) {
        val bySort = when (sort) {
            ScriptSort.UPDATED -> compareByDescending<ScriptInfo> { it.updatedAt }
            ScriptSort.NAME -> compareBy { it.name.lowercase() }
        }
        scripts.sortedWith(compareByDescending<ScriptInfo> { it.id in pinned }.then(bySort))
    }
    val filtered = remember(ordered, query, tags) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            ordered
        } else {
            ordered.filter { script ->
                script.name.contains(needle, ignoreCase = true) ||
                    tags[script.id].orEmpty().any { it.contains(needle, ignoreCase = true) }
            }
        }
    }
    // 搜索先于分组执行：空分组会被自动隐藏，搜不到时仍走原有空状态。
    val groups = remember(filtered, tags, groupMode) {
        buildScriptGroups(filtered, tags, groupMode)
    }

    val allVisibleSelected = filtered.isNotEmpty() && filtered.all { it.id in selectedIdSet }
    val allSelectedPinned = selectionMode && selectedIdSet.all { it in pinned }

    // 外部删除或刷新后丢弃已不存在的选中项；选中清空即自动退出选择模式。
    LaunchedEffect(scripts) {
        if (selectedIds.isNotEmpty()) {
            val pruned = pruneSelection(selectedIdSet, scripts)
            if (pruned != selectedIdSet) selectedIds = pruned.toList()
        }
    }

    LaunchedEffect(selectionMode) {
        if (!selectionMode) {
            showBatchTagDialog = false
            batchDeletePending = false
        }
    }

    BackHandler(enabled = selectionMode) { selectedIds = emptyList() }

    suspend fun refresh() = coroutineScope {
        loadError = false
        try {
            val scriptsDeferred = async { container.scriptRepository.listAsync() }
            val subscriptionsDeferred = async { container.subscriptionRepository.listAsync() }
            scripts = scriptsDeferred.await()
            subscriptions = subscriptionsDeferred.await().associateBy { it.scriptId }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            loadError = true
        } finally {
            loaded = true
        }
    }

    // 仓库变更（含编辑器保存 / 导入删除）后重新拉取，避免列表停留在陈旧状态。
    LaunchedEffect(revision, subscriptionRevision) { refresh() }

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
                    resources.getString(R.string.scripts_exported, target.name)
                } else {
                    resources.getString(R.string.scripts_export_failed)
                }
                snackbar.show(message)
            }
        }
    }

    val batchExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val ids = batchExportIds
        batchExportIds = emptyList()
        if (uri == null || ids.isEmpty()) return@rememberLauncherForActivityResult
        val knownScripts = scripts
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                val entries = mutableListOf<Pair<String, String>>()
                for (id in ids) {
                    val script = knownScripts.firstOrNull { it.id == id }
                        ?: container.scriptRepository.findAsync(id)
                        ?: continue
                    val content = runCatching { container.scriptRepository.read(script) }
                        .getOrNull() ?: continue
                    entries += (script.name + ".js") to content
                }
                if (entries.isEmpty()) 0 else if (writeZipToUri(context, uri, entries)) entries.size else 0
            }
            val message = if (count > 0) {
                resources.getString(R.string.scripts_batch_exported, count)
            } else {
                resources.getString(R.string.scripts_export_failed)
            }
            snackbar.show(message)
        }
    }

    fun startBatchExport() {
        val targetIds = scripts.filter { it.id in selectedIdSet }.map { it.id }
        if (targetIds.isEmpty()) return
        batchExportIds = targetIds
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        runCatching {
            batchExportLauncher.launch(resources.getString(R.string.scripts_export_zip_name, stamp))
        }.onFailure {
            batchExportIds = emptyList()
            scope.launch { snackbar.show(resources.getString(R.string.scripts_export_failed)) }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val created = withContext(Dispatchers.IO) {
                    val text = readTextFromUri(context, uri) ?: return@withContext null
                    val displayName = ScriptNameResolver.queryDisplayName(context.contentResolver, uri)
                    val name = ScriptNameResolver.fromDocument(displayName, uri.lastPathSegment)
                    container.scriptRepository.create(name, text)
                }
                if (created != null) {
                    snackbar.show(resources.getString(R.string.scripts_imported, created.name))
                } else {
                    snackbar.show(resources.getString(R.string.scripts_import_failed))
                }
            }
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val file = pendingScanFile
        pendingScanFile = null
        if (!success || file == null) {
            file?.delete()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
            val result = container.scriptImporter.importFromImage(uri)
            file.delete()
            val message = when (result) {
                is ScriptImporter.Result.Success -> {
                    resources.getString(R.string.scripts_scan_imported, result.name)
                }
                ScriptImporter.Result.NoQrCode -> resources.getString(R.string.scripts_scan_no_code)
                ScriptImporter.Result.InvalidLink -> resources.getString(R.string.scripts_scan_invalid_link)
                ScriptImporter.Result.DownloadFailed -> resources.getString(R.string.scripts_scan_download_failed)
            }
            snackbar.show(message)
        }
    }

    fun startScanImport() {
        showOverflow = false
        runCatching {
            val dir = File(context.cacheDir, "captures").apply { mkdirs() }
            val file = File.createTempFile("scan_", ".jpg", dir)
            pendingScanFile = file
            val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
            scanLauncher.launch(uri)
        }.onFailure {
            pendingScanFile?.delete()
            pendingScanFile = null
            scope.launch { snackbar.show(resources.getString(R.string.scripts_scan_failed)) }
        }
    }

    val scriptRow: @Composable (ScriptInfo) -> Unit = { script ->
        ScriptRow(
            script = script,
            subscription = subscriptions[script.id],
            tags = tags[script.id].orEmpty(),
            pinned = script.id in pinned,
            pinnedToTile = script.id == tileScriptId,
            selectionMode = selectionMode,
            selected = script.id in selectedIdSet,
            onClick = {
                if (selectionMode) {
                    selectedIds = toggleSelection(selectedIdSet, script.id).toList()
                } else {
                    onOpenScript(script.id)
                }
            },
            onLongClick = {
                haptics.toggle()
                selectedIds = (selectedIdSet + script.id).toList()
            },
            onTogglePin = {
                haptics.toggle()
                container.scriptListPreferences.togglePinned(script.id)
            },
            onToggleTile = {
                haptics.toggle()
                container.tileSettingsRepository.setScriptId(
                    if (tileScriptId == script.id) null else script.id,
                )
            },
            onEditTags = {
                tagTargetId = script.id
                tagInput = tags[script.id].orEmpty().joinToString(", ")
            },
            onEdit = { onEditScript(script.id) },
            onExport = { exportTarget = script; exportLauncher.launch(script.name + ".js") },
            onDelete = { deleteTarget = script },
        )
    }

    IzukiPageScaffold(
        title = {
            Text(
                if (selectionMode) {
                    stringResource(R.string.scripts_selected_count, selectedIds.size)
                } else {
                    stringResource(R.string.scripts_title)
                },
            )
        },
        onBack = if (selectionMode) ({ selectedIds = emptyList() }) else null,
        actions = {
            if (selectionMode) {
                TextButton(
                    onClick = {
                        selectedIds = toggleSelectAllVisible(selectedIdSet, filtered).toList()
                    },
                ) {
                    Text(
                        stringResource(
                            if (allVisibleSelected) {
                                R.string.scripts_unselect_all
                            } else {
                                R.string.scripts_select_all
                            },
                        ),
                    )
                }
            } else {
                IconButton(onClick = { startScanImport() }) {
                    Icon(
                        painterResource(R.drawable.ic_qr_code_scanner),
                        contentDescription = stringResource(R.string.scripts_scan_import),
                    )
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
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.scripts_flows)) },
                            onClick = { showOverflow = false; onOpenFlows() },
                        )
                        HorizontalDivider()
                        CheckedMenuItem(
                            label = stringResource(R.string.scripts_sort_updated),
                            selected = sort == ScriptSort.UPDATED,
                            onClick = {
                                container.scriptListPreferences.setSort(ScriptSort.UPDATED)
                                showOverflow = false
                            },
                        )
                        CheckedMenuItem(
                            label = stringResource(R.string.scripts_sort_name),
                            selected = sort == ScriptSort.NAME,
                            onClick = {
                                container.scriptListPreferences.setSort(ScriptSort.NAME)
                                showOverflow = false
                            },
                        )
                        HorizontalDivider()
                        CheckedMenuItem(
                            label = stringResource(R.string.scripts_group_none),
                            selected = groupMode == ScriptGroupMode.NONE,
                            onClick = {
                                container.scriptListPreferences.setGroupMode(ScriptGroupMode.NONE)
                                showOverflow = false
                            },
                        )
                        CheckedMenuItem(
                            label = stringResource(R.string.scripts_group_by_tag),
                            selected = groupMode == ScriptGroupMode.TAG,
                            onClick = {
                                container.scriptListPreferences.setGroupMode(ScriptGroupMode.TAG)
                                showOverflow = false
                            },
                        )
                    }
                }
            }
        },
        floatingActionButton = if (selectionMode) {
            null
        } else {
            {
                FloatingActionButton(onClick = { newName = defaultName; showNewDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.scripts_new))
                }
            }
        },
        bottomBar = if (selectionMode) {
            {
                ScriptSelectionBar(
                    allPinned = allSelectedPinned,
                    onTogglePin = {
                        haptics.toggle()
                        container.scriptListPreferences.setPinnedAll(selectedIdSet, !allSelectedPinned)
                    },
                    onEditTags = {
                        batchTagMode = BATCH_TAG_ADD
                        batchTagInput = ""
                        showBatchTagDialog = true
                    },
                    onExport = { startBatchExport() },
                    onDelete = { batchDeletePending = true },
                )
            }
        } else {
            null
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(
            modifier = Modifier.widthIn(max = PageMaxWidth).fillMaxWidth().fillMaxSize(),
        ) {
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

                    loadError && scripts.isEmpty() -> EmptyState(
                        text = stringResource(R.string.scripts_load_failed),
                        icon = Icons.Filled.Refresh,
                        actionLabel = stringResource(R.string.common_retry),
                        onAction = { scope.launch { refresh() } },
                    )

                    scripts.isEmpty() -> EmptyState(
                        text = stringResource(R.string.scripts_empty),
                        icon = Icons.Filled.Add,
                        actionLabel = stringResource(R.string.scripts_new),
                        onAction = {
                            newName = defaultName
                            showNewDialog = true
                        },
                    )

                    filtered.isEmpty() -> EmptyState(
                        text = stringResource(R.string.scripts_no_match, query.trim()),
                        actionLabel = stringResource(R.string.common_clear),
                        onAction = { query = "" },
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 88.dp),
                    ) {
                        if (groupMode == ScriptGroupMode.NONE) {
                            items(filtered, key = { it.id }) { script -> scriptRow(script) }
                        } else {
                            groups.forEach { group ->
                                stickyHeader(
                                    key = "header:${group.key}",
                                    contentType = "header",
                                ) { _ ->
                                    ScriptGroupHeader(tag = group.tag, count = group.scripts.size)
                                }
                                items(
                                    items = group.scripts,
                                    key = { script -> "${group.key}\u0000${script.id}" },
                                ) { script -> scriptRow(script) }
                            }
                        }
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
                    isError = newName.isBlank(),
                    supportingText = if (newName.isBlank()) {
                        { Text(stringResource(R.string.script_edit_name_required)) }
                    } else {
                        null
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (submitting || newName.isBlank()) return@TextButton
                        submitting = true
                        scope.launch {
                            val created = container.scriptRepository.createAsync(newName)
                            showNewDialog = false
                            submitting = false
                            onOpenScript(created.id)
                        }
                    },
                    enabled = !submitting && newName.isNotBlank(),
                ) { Text(stringResource(R.string.scripts_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    tagTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { tagTargetId = null },
            title = { Text(stringResource(R.string.scripts_edit_tags)) },
            text = {
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.scripts_tags_label)) },
                    placeholder = { Text(stringResource(R.string.scripts_tags_placeholder)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    container.scriptListPreferences.setTags(target.id, parseTagInput(tagInput))
                    tagTargetId = null
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { tagTargetId = null }) { Text(stringResource(R.string.common_cancel)) }
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
                    // 使用应用级作用域：切到其它标签后 Snackbar 仍可点击“撤销”。
                    container.uiScope.launch {
                        // 删除与撤销的级联逻辑集中在 ScriptDeletion，页面只负责弹窗与 Snackbar。
                        // 页面切走会取消组合作用域；级联删除不能停在一半。
                        val backup = withContext(NonCancellable) {
                            deletion.delete(
                                script = target,
                                tags = tags[target.id].orEmpty(),
                                pinned = target.id in pinned,
                                pinnedToTile = target.id == tileScriptId,
                            )
                        }
                        if (backup == null) {
                            snackbar.show(
                                resources.getString(R.string.scripts_delete_failed, target.name),
                            )
                            return@launch
                        }
                        val result = snackbar.show(
                            message = resources.getString(R.string.scripts_deleted, target.name),
                            actionLabel = resources.getString(R.string.common_undo),
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            withContext(NonCancellable) { deletion.restore(backup) }
                        }
                    }
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showBatchTagDialog && selectionMode) {
        AlertDialog(
            onDismissRequest = { showBatchTagDialog = false },
            title = {
                Text(
                    stringResource(
                        if (batchTagMode == BATCH_TAG_REMOVE) {
                            R.string.scripts_batch_remove_tags
                        } else {
                            R.string.scripts_batch_add_tags
                        },
                    ),
                )
            },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = batchTagMode == BATCH_TAG_ADD,
                            onClick = { batchTagMode = BATCH_TAG_ADD },
                            label = { Text(stringResource(R.string.scripts_tags_add)) },
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = batchTagMode == BATCH_TAG_REMOVE,
                            onClick = { batchTagMode = BATCH_TAG_REMOVE },
                            label = { Text(stringResource(R.string.scripts_tags_remove)) },
                        )
                    }
                    OutlinedTextField(
                        value = batchTagInput,
                        onValueChange = { batchTagInput = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.scripts_tags_label)) },
                        placeholder = { Text(stringResource(R.string.scripts_tags_placeholder)) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val input = parseTagInput(batchTagInput)
                    if (input.isNotEmpty()) {
                        if (batchTagMode == BATCH_TAG_REMOVE) {
                            container.scriptListPreferences.removeTagsFrom(selectedIdSet, input)
                        } else {
                            container.scriptListPreferences.addTagsTo(selectedIdSet, input)
                        }
                    }
                    showBatchTagDialog = false
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showBatchTagDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (batchDeletePending && selectionMode) {
        ConfirmDialog(
            title = stringResource(R.string.scripts_delete_title),
            message = stringResource(R.string.scripts_batch_delete_message, selectedIds.size),
            destructive = true,
            onDismiss = { batchDeletePending = false },
            onConfirm = {
                val targets = scripts.filter { it.id in selectedIdSet }
                val tagsSnapshot = tags
                val pinnedSnapshot = pinned
                val tileSnapshot = tileScriptId
                batchDeletePending = false
                selectedIds = emptyList()
                if (targets.isEmpty()) return@ConfirmDialog
                haptics.reject()
                // 与单条删除一致：使用应用级作用域，切页后仍可撤销；级联删除不可中断。
                container.uiScope.launch {
                    val outcome = withContext(NonCancellable) {
                        deletion.deleteAll(
                            scripts = targets,
                            tagsOf = { tagsSnapshot[it].orEmpty() },
                            pinnedOf = { it in pinnedSnapshot },
                            pinnedToTileId = tileSnapshot,
                        )
                    }
                    if (outcome.backups.isEmpty()) {
                        snackbar.show(
                            resources.getString(
                                R.string.scripts_batch_delete_failed,
                                outcome.failedNames.size,
                            ),
                        )
                        return@launch
                    }
                    val message = if (outcome.failedNames.isEmpty()) {
                        resources.getString(R.string.scripts_batch_deleted, outcome.backups.size)
                    } else {
                        resources.getString(
                            R.string.scripts_batch_deleted_partial,
                            outcome.backups.size,
                            outcome.failedNames.size,
                        )
                    }
                    val result = snackbar.show(
                        message = message,
                        actionLabel = resources.getString(R.string.common_undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        withContext(NonCancellable) { deletion.restoreAll(outcome.backups) }
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScriptRow(
    script: ScriptInfo,
    subscription: ScriptSubscription?,
    tags: List<String>,
    pinned: Boolean,
    pinnedToTile: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleTile: () -> Unit,
    onEditTags: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
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
        },
    )
}

/** 多选模式底部操作栏；破坏性操作使用错误色，与其它删除入口保持一致。 */
@Composable
private fun ScriptSelectionBar(
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

/**
 * 按标签生成分组。一个脚本有多个标签时会出现在每个标签组中；
 * 无标签脚本统一归入最后的「未分类」组，空组不返回。
 */
internal fun buildScriptGroups(
    scripts: List<ScriptInfo>,
    tags: Map<String, List<String>>,
    mode: ScriptGroupMode,
): List<ScriptGroup> {
    if (mode == ScriptGroupMode.NONE) return listOf(ScriptGroup("all", null, scripts))

    val byTag = LinkedHashMap<String, MutableList<ScriptInfo>>()
    val untagged = mutableListOf<ScriptInfo>()
    scripts.forEach { script ->
        val scriptTags = tags[script.id].orEmpty()
        if (scriptTags.isEmpty()) {
            untagged += script
        } else {
            scriptTags.distinct().forEach { tag ->
                byTag.getOrPut(tag) { mutableListOf() } += script
            }
        }
    }

    return buildList {
        byTag.keys.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { tag ->
            add(ScriptGroup("tag:$tag", tag, byTag.getValue(tag)))
        }
        if (untagged.isNotEmpty()) add(ScriptGroup("untagged", null, untagged))
    }
}

@Composable
private fun ScriptGroupHeader(tag: String?, count: Int) {
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
private fun CheckedMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
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
private fun ShimmerBox(alpha: Float, width: Dp, height: Dp) {
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.5f),
                RoundedCornerShape(4.dp),
            ),
    )
}

private fun parseTagInput(raw: String): List<String> = raw
    .split(',', '，', ';', '；', '\n')
    .map { it.trim() }
    .filter { it.isNotEmpty() }

private fun writeTextToUri(context: Context, uri: Uri, text: String): Boolean = runCatching {
    val stream = context.contentResolver.openOutputStream(uri) ?: return@runCatching false
    stream.use { it.write(text.toByteArray()) }
    true
}.getOrDefault(false)

/** 把多个脚本打包为 zip；单个条目读取失败不会中断导出。 */
private fun writeZipToUri(
    context: Context,
    uri: Uri,
    entries: List<Pair<String, String>>,
): Boolean = runCatching {
    val stream = context.contentResolver.openOutputStream(uri) ?: return@runCatching false
    ZipOutputStream(stream.buffered()).use { zip ->
        entries.forEach { (name, content) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
    }
    true
}.getOrDefault(false)

private const val BATCH_TAG_ADD = "add"
private const val BATCH_TAG_REMOVE = "remove"

private fun readTextFromUri(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}.getOrNull()
