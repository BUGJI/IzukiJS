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
import androidx.compose.foundation.layout.PaddingValues
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
import com.benton.izukijs.data.ScriptGroupMode
import com.benton.izukijs.data.ScriptImporter
import com.benton.izukijs.data.ScriptSort
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.subscription.ScriptSubscription
import com.benton.izukijs.ui.common.EmptyState
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.formatDateTime
import com.benton.izukijs.ui.common.intervalLabel
import com.benton.izukijs.ui.common.rememberAppHaptics
import com.benton.izukijs.ui.common.stableTopAppBarColors
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreen(
    onOpenScript: (String) -> Unit,
    onEditScript: (String) -> Unit,
    onOpenSchedule: () -> Unit,
) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarController.current
    val haptics = rememberAppHaptics()
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
    var showNewDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var showOverflow by remember { mutableStateOf(false) }
    var exportTarget by remember { mutableStateOf<ScriptInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<ScriptInfo?>(null) }
    var tagTarget by remember { mutableStateOf<ScriptInfo?>(null) }
    var tagInput by remember { mutableStateOf("") }
    var pendingScanFile by remember { mutableStateOf<File?>(null) }
    var query by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

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

    suspend fun refresh() = coroutineScope {
        val scriptsDeferred = async { container.scriptRepository.listAsync() }
        val subscriptionsDeferred = async { container.subscriptionRepository.listAsync() }
        scripts = scriptsDeferred.await()
        subscriptions = subscriptionsDeferred.await().associateBy { it.scriptId }
        loaded = true
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
                    val created = container.scriptRepository.createAsync(name, text)
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
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
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
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
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
            onClick = { onOpenScript(script.id) },
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
                tagTarget = script
                tagInput = tags[script.id].orEmpty().joinToString(", ")
            },
            onEdit = { onEditScript(script.id) },
            onExport = { exportTarget = script; exportLauncher.launch(script.name + ".js") },
            onDelete = { deleteTarget = script },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scripts_title)) },
                scrollBehavior = scrollBehavior,
                colors = stableTopAppBarColors(),
                actions = {
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
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { newName = defaultName; showNewDialog = true }) {
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
                    enabled = !submitting,
                ) { Text(stringResource(R.string.scripts_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    tagTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { tagTarget = null },
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
                    tagTarget = null
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { tagTarget = null }) { Text(stringResource(R.string.common_cancel)) }
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
                        val useGlobalEnv = container.scriptEnvRepository.useGlobalEnv(target.id)
                        val env = container.scriptEnvRepository.env(target.id)
                        val envText = container.scriptEnvRepository.envText(target.id)
                        val savedTags = tags[target.id].orEmpty()
                        val wasPinned = target.id in pinned
                        val wasTile = target.id == tileScriptId
                        val savedSubscription = container.subscriptionRepository.getAsync(target.id)
                        val savedSchedules = container.scheduleRepository.listAsync()
                            .filter { it.scriptId == target.id }
                        container.subscriptionManager.cancelAsync(target.id)
                        savedSchedules.forEach { container.scheduleManager.cancelAsync(it.id) }
                        container.scriptRepository.deleteAsync(target)
                        container.scriptEnvRepository.remove(target.id)
                        container.scriptListPreferences.removeTags(target.id)
                        container.scriptListPreferences.setPinned(target.id, false)
                        if (wasTile) container.tileSettingsRepository.setScriptId(null)
                        val result = snackbar.show(
                            message = resources.getString(R.string.scripts_deleted, target.name),
                            actionLabel = resources.getString(R.string.common_undo),
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            val restored = container.scriptRepository.createAsync(target.name, content)
                            if (savedTags.isNotEmpty()) {
                                container.scriptListPreferences.setTags(restored.id, savedTags)
                            }
                            if (values.isNotEmpty()) container.scriptEnvRepository.saveValues(restored.id, values)
                            state.forEach { (key, value) ->
                                container.scriptEnvRepository.putState(restored.id, key, value)
                            }
                            container.scriptEnvRepository.saveEnvSettings(
                                restored.id,
                                useGlobalEnv,
                                env,
                                envText,
                            )
                            if (wasPinned) container.scriptListPreferences.setPinned(restored.id, true)
                            if (wasTile) container.tileSettingsRepository.setScriptId(restored.id)
                            savedSubscription?.let {
                                container.subscriptionManager.scheduleAsync(it.copy(scriptId = restored.id))
                            }
                            savedSchedules.forEach { schedule ->
                                container.scheduleManager.scheduleAsync(
                                    schedule.copy(scriptId = restored.id, scriptName = restored.name),
                                )
                            }
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
    subscription: ScriptSubscription?,
    tags: List<String>,
    pinned: Boolean,
    pinnedToTile: Boolean,
    onClick: () -> Unit,
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
        supportingContent = {
            val parts = buildList {
                add(formatDateTime(script.updatedAt))
                val shown = tags.take(3).joinToString("  ") { "#$it" }
                val tagText = shown + if (tags.size > 3) " +${tags.size - 3}" else ""
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
            .clickable { onClick() },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (pinnedToTile) R.string.scripts_tile_unpin else R.string.scripts_tile_pin,
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
        },
    )
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

private fun readTextFromUri(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}.getOrNull()
