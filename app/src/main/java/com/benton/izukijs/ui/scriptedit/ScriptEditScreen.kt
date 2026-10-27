package com.benton.izukijs.ui.scriptedit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.model.EnvParseResult
import com.benton.izukijs.model.EnvText
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.subscription.ScriptSubscription
import com.benton.izukijs.subscription.SubscriptionSync
import com.benton.izukijs.ui.common.EmptyState
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.PageColumn
import com.benton.izukijs.ui.common.PageMaxWidth
import com.benton.izukijs.ui.common.SwitchRow
import com.benton.izukijs.ui.common.formatDateTime
import com.benton.izukijs.ui.common.stableTopAppBarColors
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.launch

private enum class ScriptEditTab(val labelRes: Int) {
    ENVIRONMENT(R.string.script_edit_tab_env),
    SUBSCRIPTION(R.string.script_edit_tab_subscription),
}

private data class ScriptEditBaseline(
    val name: String,
    val url: String,
    val intervalText: String,
    val enabled: Boolean,
    val useGlobalEnv: Boolean,
    val envText: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptEditScreen(scriptId: String, onBack: () -> Unit) {
    val container = rememberAppContainer()
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarController.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val pagerState = rememberPagerState(pageCount = { ScriptEditTab.entries.size })
    val globalEnv by container.globalEnvRepository.values.collectAsStateWithLifecycle()

    var currentScriptId by rememberSaveable { mutableStateOf(scriptId) }
    var script by remember { mutableStateOf<ScriptInfo?>(null) }
    var loaded by rememberSaveable(scriptId) { mutableStateOf(false) }
    var name by rememberSaveable(scriptId) { mutableStateOf("") }
    var url by rememberSaveable(scriptId) { mutableStateOf("") }
    var intervalText by rememberSaveable(scriptId) { mutableStateOf("0") }
    var enabled by rememberSaveable(scriptId) { mutableStateOf(false) }
    var useGlobalEnv by rememberSaveable(scriptId) { mutableStateOf(true) }
    var envText by rememberSaveable(scriptId) { mutableStateOf("") }
    var envErrorLine by rememberSaveable(scriptId) { mutableStateOf<Int?>(null) }
    var subscription by remember { mutableStateOf<ScriptSubscription?>(null) }
    var baseline by remember { mutableStateOf<ScriptEditBaseline?>(null) }
    var saving by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showDiscard by rememberSaveable { mutableStateOf(false) }

    fun loadEnvText(id: String): String {
        val raw = container.scriptEnvRepository.envText(id)
        return raw.ifBlank { EnvText.format(container.scriptEnvRepository.env(id)) }
    }

    LaunchedEffect(currentScriptId) {
        val found = container.scriptRepository.findAsync(currentScriptId)
        script = found
        if (found == null) {
            loaded = true
            baseline = null
            return@LaunchedEffect
        }

        val diskUseGlobal = container.scriptEnvRepository.useGlobalEnv(found.id)
        val diskEnvText = loadEnvText(found.id)
        val existing = container.subscriptionRepository.getAsync(found.id)
        subscription = existing

        if (!loaded) {
            name = found.name
            useGlobalEnv = diskUseGlobal
            envText = diskEnvText
            if (existing != null) {
                url = existing.url
                intervalText = existing.intervalMinutes.toString()
                enabled = existing.enabled && existing.intervalMinutes > 0L
            } else {
                url = ""
                intervalText = "0"
                enabled = false
            }
            loaded = true
        }

        baseline = ScriptEditBaseline(
            name = found.name,
            url = existing?.url.orEmpty().trim(),
            intervalText = (existing?.intervalMinutes ?: 0L).toString(),
            enabled = existing != null && existing.enabled && existing.intervalMinutes > 0L,
            useGlobalEnv = diskUseGlobal,
            envText = diskEnvText,
        )
    }

    val intervalValue = intervalText.toLongOrNull()
    val urlInvalid = url.isNotBlank() && !SubscriptionSync.isHttpUrl(url)
    val intervalDisabled = url.isBlank()
    val intervalInvalid = !intervalDisabled &&
        (intervalValue == null ||
            (intervalValue > 0L && intervalValue < SubscriptionSync.MIN_INTERVAL_MINUTES))
    val dirty = baseline?.let { saved ->
        saved != ScriptEditBaseline(
            name = name,
            url = url.trim(),
            intervalText = intervalText,
            enabled = enabled,
            useGlobalEnv = useGlobalEnv,
            envText = envText,
        )
    } ?: false
    val syncReady = subscription != null &&
        !intervalDisabled &&
        url.trim() == subscription?.url?.trim() &&
        (intervalValue ?: 0L) > 0L &&
        !urlInvalid &&
        !intervalInvalid &&
        !syncing &&
        !deleting

    fun save(onSaved: (() -> Unit)? = null) {
        val current = script ?: return
        if (saving) return
        val newName = name.trim()
        if (newName.isEmpty()) {
            scope.launch { snackbar.show(resources.getString(R.string.script_edit_name_required)) }
            return
        }
        val envForSave = envText
        val useGlobalForSave = useGlobalEnv
        val parsedEnv = when (val result = EnvText.parse(envForSave)) {
            is EnvParseResult.Failure -> {
                envErrorLine = result.lineNumber
                scope.launch {
                    snackbar.show(resources.getString(R.string.env_invalid_line, result.lineNumber))
                }
                return
            }

            is EnvParseResult.Success -> result.values
        }
        envErrorLine = null
        val trimmedUrl = url.trim()
        if (trimmedUrl.isNotEmpty() && !SubscriptionSync.isHttpUrl(trimmedUrl)) {
            scope.launch { snackbar.show(resources.getString(R.string.subscription_url_invalid)) }
            return
        }
        if (trimmedUrl.isNotEmpty() && intervalInvalid) {
            scope.launch {
                snackbar.show(
                    resources.getString(
                        R.string.subscription_interval_invalid,
                        SubscriptionSync.MIN_INTERVAL_MINUTES.toInt(),
                    ),
                )
            }
            return
        }

        val interval = if ((intervalValue ?: 0L) <= 0L) {
            0L
        } else {
            SubscriptionSync.normalizeInterval(intervalValue)
        }
        val effectiveEnabled = enabled && interval > 0L
        val hadSubscription = subscription != null
        saving = true
        scope.launch {
            var message = resources.getString(R.string.script_saved)
            var failed = false
            var shouldSync = false
            var syncTargetId: String? = null
            runCatching {
                var target = current
                var renamedId: String? = null
                if (newName != current.name) {
                    target = container.scriptRepository.renameAsync(current, newName)
                    container.scriptEnvRepository.move(current.id, target.id)
                    container.scriptListPreferences.moveTags(current.id, target.id)
                    container.scriptListPreferences.movePinned(current.id, target.id)
                    container.tileSettingsRepository.moveScriptId(current.id, target.id)
                    container.scheduleRepository.moveScriptAsync(current.id, target.id, target.name)
                    container.subscriptionManager.moveAsync(current.id, target.id)
                    script = target
                    renamedId = target.id
                }

                container.scriptEnvRepository.saveEnvSettings(
                    target.id,
                    useGlobalForSave,
                    parsedEnv,
                    envForSave,
                )

                if (trimmedUrl.isEmpty()) {
                    if (hadSubscription) {
                        container.subscriptionManager.cancelAsync(target.id)
                        subscription = null
                        message = resources.getString(R.string.subscription_deleted)
                    }
                    url = ""
                    intervalText = "0"
                    enabled = false
                } else {
                    val existing = container.subscriptionRepository.getAsync(target.id)
                    val saved = ScriptSubscription(
                        scriptId = target.id,
                        url = trimmedUrl,
                        intervalMinutes = interval,
                        enabled = effectiveEnabled,
                        lastSyncAtMillis = existing?.lastSyncAtMillis ?: 0L,
                        nextSyncAtMillis = existing?.nextSyncAtMillis ?: 0L,
                        lastError = existing?.lastError.orEmpty(),
                        contentHash = existing?.contentHash.orEmpty(),
                        etag = existing?.etag.orEmpty(),
                        lastModified = existing?.lastModified.orEmpty(),
                    )
                    container.subscriptionManager.scheduleAsync(saved)
                    subscription = container.subscriptionRepository.getAsync(target.id)
                    shouldSync = (existing == null || existing.url != trimmedUrl) && effectiveEnabled
                    syncTargetId = target.id
                    intervalText = interval.toString()
                    enabled = effectiveEnabled
                    message = resources.getString(
                        if (!effectiveEnabled) {
                            R.string.subscription_saved_disabled
                        } else {
                            R.string.subscription_saved
                        },
                    )
                }
                name = target.name
                url = trimmedUrl
                renamedId?.let { currentScriptId = it }
                baseline = ScriptEditBaseline(
                    name = target.name,
                    url = trimmedUrl,
                    intervalText = intervalText,
                    enabled = enabled,
                    useGlobalEnv = useGlobalForSave,
                    envText = envForSave,
                )
            }.onFailure { error ->
                failed = true
                message = error.message ?: resources.getString(R.string.subscription_sync_failed)
            }
            saving = false
            if (failed) {
                snackbar.show(message)
            } else {
                val idToSync = syncTargetId
                if (shouldSync && idToSync != null) {
                    syncing = true
                    container.subscriptionManager.syncNowAsync(idToSync) { ok ->
                        scope.launch {
                            if (deleting || subscription == null) {
                                syncing = false
                                return@launch
                            }
                            subscription = container.subscriptionRepository.getAsync(idToSync)
                            syncing = false
                            if (!ok) {
                                snackbar.show(resources.getString(R.string.subscription_sync_failed))
                            }
                        }
                    }
                }
                if (onSaved != null) {
                    onSaved()
                } else {
                    snackbar.show(message)
                }
            }
        }
    }

    fun syncNow() {
        val current = script ?: return
        if (!syncReady) return
        syncing = true
        container.subscriptionManager.syncNowAsync(current.id) { ok ->
            scope.launch {
                if (deleting || subscription == null) {
                    syncing = false
                    return@launch
                }
                subscription = container.subscriptionRepository.getAsync(current.id)
                syncing = false
                snackbar.show(
                    resources.getString(
                        if (ok) R.string.subscription_sync_success else R.string.subscription_sync_failed,
                    ),
                )
            }
        }
    }

    fun deleteSubscription() {
        val current = script ?: return
        if (deleting) return
        deleting = true
        scope.launch {
            try {
                container.subscriptionManager.cancelAsync(current.id)
                subscription = null
                url = ""
                intervalText = "0"
                enabled = false
                baseline = baseline?.copy(url = "", intervalText = "0", enabled = false)
            } finally {
                deleting = false
                syncing = false
            }
            snackbar.show(resources.getString(R.string.subscription_deleted))
        }
    }

    fun attemptBack() {
        if (saving) return
        if (dirty) showDiscard = true else onBack()
    }

    BackHandler(enabled = !showDelete && !showDiscard) { attemptBack() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            script?.let { stringResource(R.string.script_edit_title, it.name) }
                                ?: stringResource(R.string.script_edit_title_fallback),
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
                scrollBehavior = scrollBehavior,
                colors = stableTopAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = { attemptBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { save() },
                        enabled = loaded && script != null && !saving,
                    ) {
                        Text(stringResource(R.string.common_save))
                    }
                },
            )
        },
    ) { padding ->
        when {
            !loaded -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            script == null -> EmptyState(
                text = stringResource(R.string.script_edit_not_found),
                modifier = Modifier.padding(padding),
            )

            else -> Box(
                modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = PageMaxWidth)
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.script_edit_name)) },
                        singleLine = true,
                        isError = name.isBlank(),
                        supportingText = if (name.isBlank()) {
                            { Text(stringResource(R.string.script_edit_name_required)) }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    )
                    PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                        ScriptEditTab.entries.forEachIndexed { index, tab ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                text = { Text(stringResource(tab.labelRes)) },
                            )
                        }
                    }
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f),
                    ) { page ->
                        when (ScriptEditTab.entries[page]) {
                            ScriptEditTab.ENVIRONMENT -> EnvironmentTab(
                                useGlobalEnv = useGlobalEnv,
                                onUseGlobalEnvChange = { useGlobalEnv = it },
                                envText = envText,
                                onEnvTextChange = {
                                    envText = it
                                    envErrorLine = null
                                },
                                globalCount = globalEnv.size,
                                errorLine = envErrorLine,
                            )

                            ScriptEditTab.SUBSCRIPTION -> SubscriptionTab(
                                subscription = subscription,
                                url = url,
                                onUrlChange = { newUrl ->
                                    val wasBlank = url.isBlank()
                                    url = newUrl
                                    if (newUrl.isBlank()) {
                                        intervalText = "0"
                                        enabled = false
                                    } else if (wasBlank) {
                                        if ((intervalText.toLongOrNull() ?: 0L) <= 0L) {
                                            intervalText =
                                                SubscriptionSync.DEFAULT_INTERVAL_MINUTES.toString()
                                        }
                                        enabled = true
                                    }
                                },
                                urlInvalid = urlInvalid,
                                enabled = enabled,
                                onEnabledChange = { checked ->
                                    if ((intervalText.toLongOrNull() ?: 0L) > 0L) enabled = checked
                                },
                                intervalText = intervalText,
                                onIntervalChange = { raw ->
                                    val previous = intervalText.toLongOrNull() ?: 0L
                                    intervalText = raw
                                    val next = raw.toLongOrNull() ?: 0L
                                    if (next <= 0L) {
                                        enabled = false
                                    } else if (previous <= 0L) {
                                        enabled = true
                                    }
                                },
                                intervalDisabled = intervalDisabled,
                                intervalInvalid = intervalInvalid,
                                syncing = syncing,
                                syncReady = syncReady,
                                deleting = deleting,
                                onSyncNow = { syncNow() },
                                onDelete = { showDelete = true },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.subscription_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.subscription_delete_confirm,
                        script?.name ?: resources.getString(R.string.script_edit_title_fallback),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    deleteSubscription()
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text(stringResource(R.string.script_edit_discard_title)) },
            text = { Text(stringResource(R.string.script_edit_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscard = false
                    save(onSaved = onBack)
                }) { Text(stringResource(R.string.script_edit_save_and_back)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showDiscard = false
                        onBack()
                    }) { Text(stringResource(R.string.script_edit_discard)) }
                    TextButton(onClick = { showDiscard = false }) {
                        Text(stringResource(R.string.script_edit_continue))
                    }
                }
            },
        )
    }
}

@Composable
private fun EnvironmentTab(
    useGlobalEnv: Boolean,
    onUseGlobalEnvChange: (Boolean) -> Unit,
    envText: String,
    onEnvTextChange: (String) -> Unit,
    globalCount: Int,
    errorLine: Int?,
) {
    PageColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        SwitchRow(
            label = stringResource(R.string.script_edit_env_use_global),
            checked = useGlobalEnv,
            description = stringResource(R.string.script_edit_env_use_global_desc, globalCount),
            onChange = onUseGlobalEnvChange,
        )
        OutlinedTextField(
            value = envText,
            onValueChange = onEnvTextChange,
            label = { Text(stringResource(R.string.script_edit_env_variables)) },
            placeholder = { Text(stringResource(R.string.env_example)) },
            isError = errorLine != null,
            supportingText = {
                if (errorLine != null) {
                    Text(stringResource(R.string.env_invalid_line, errorLine))
                } else {
                    Text(stringResource(R.string.env_hint))
                }
            },
            minLines = 8,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SubscriptionTab(
    subscription: ScriptSubscription?,
    url: String,
    onUrlChange: (String) -> Unit,
    urlInvalid: Boolean,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    intervalText: String,
    onIntervalChange: (String) -> Unit,
    intervalDisabled: Boolean,
    intervalInvalid: Boolean,
    syncing: Boolean,
    syncReady: Boolean,
    deleting: Boolean,
    onSyncNow: () -> Unit,
    onDelete: () -> Unit,
) {
    PageColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.subscription_url)) },
            placeholder = { Text(stringResource(R.string.subscription_url_hint)) },
            singleLine = true,
            isError = urlInvalid,
            supportingText = {
                if (urlInvalid) {
                    Text(stringResource(R.string.subscription_url_invalid))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        val currentInterval = intervalText.toLongOrNull()
        val switchEnabled = !intervalDisabled && (currentInterval ?: 0L) > 0L
        OutlinedTextField(
            value = intervalText,
            onValueChange = { onIntervalChange(it.filter(Char::isDigit).take(6)) },
            label = { Text(stringResource(R.string.subscription_interval)) },
            singleLine = true,
            enabled = !intervalDisabled,
            isError = intervalInvalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                Text(
                    when {
                        intervalDisabled -> stringResource(R.string.subscription_interval_needs_url)
                        intervalInvalid -> stringResource(
                            R.string.subscription_interval_invalid,
                            SubscriptionSync.MIN_INTERVAL_MINUTES.toInt(),
                        )

                        (currentInterval ?: 0L) <= 0L -> stringResource(
                            R.string.subscription_interval_zero_hint,
                        )

                        else -> stringResource(
                            R.string.subscription_min_interval,
                            SubscriptionSync.MIN_INTERVAL_MINUTES.toInt(),
                        )
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        SwitchRow(
            label = stringResource(R.string.subscription_enabled),
            checked = enabled && (currentInterval ?: 0L) > 0L,
            description = stringResource(R.string.subscription_disabled_hint),
            enabled = switchEnabled,
            onChange = onEnabledChange,
        )

        if (subscription == null) {
            Text(
                stringResource(R.string.subscription_not_configured),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (subscription.lastSyncAtMillis > 0L) {
                        stringResource(
                            R.string.subscription_last_sync,
                            formatDateTime(subscription.lastSyncAtMillis),
                        )
                    } else {
                        stringResource(R.string.subscription_never)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(
                        R.string.subscription_next_sync,
                        if (subscription.enabled &&
                            subscription.intervalMinutes > 0L &&
                            subscription.nextSyncAtMillis > 0L
                        ) {
                            formatDateTime(subscription.nextSyncAtMillis)
                        } else {
                            stringResource(R.string.subscription_next_none)
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (subscription.lastError.isNotBlank()) {
                    Text(
                        stringResource(R.string.subscription_error, subscription.lastError),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onSyncNow,
                enabled = syncReady,
            ) {
                if (syncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.subscription_sync_now))
            }
            OutlinedButton(
                onClick = onDelete,
                enabled = subscription != null && !deleting,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.subscription_delete))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
