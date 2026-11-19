package com.benton.izukijs.ui.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.core.log.LogLevel
import com.benton.izukijs.core.perf.PerfMetrics
import com.benton.izukijs.core.permission.SystemPermissionChecks
import com.benton.izukijs.data.ScriptStoragePaths
import com.benton.izukijs.data.ScriptStorageSwitchResult
import com.benton.izukijs.runtime.CacheImageStore
import com.benton.izukijs.ui.common.ActionButtons
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.ConfirmDialog
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SectionGap
import com.benton.izukijs.ui.common.SwitchRow
import com.benton.izukijs.ui.common.formatFileSize
import com.benton.izukijs.ui.common.newCacheExportFile
import com.benton.izukijs.ui.common.shareFile
import com.benton.izukijs.ui.appViewModel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun StorageSettings() {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val logSettings = ui.logSettings
    var logFileSize by remember { mutableStateOf(0L) }
    var cacheImages by remember { mutableStateOf(CacheImageStore.Stats(0, 0L)) }
    var confirmClearLog by remember { mutableStateOf(false) }
    var confirmClearCacheImages by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val snackbar = LocalSnackbarController.current

    val currentRoot = remember(ui.scriptStorage) { viewModel.currentScriptStorageRoot() }
    var showStoragePicker by remember { mutableStateOf(false) }
    var pendingStorageRoot by remember { mutableStateOf<String?>(null) }
    var showStorageSwitch by remember { mutableStateOf(false) }
    var copyStorageScripts by remember { mutableStateOf(true) }
    var showStoragePermission by remember { mutableStateOf(false) }
    var switchingStorage by remember { mutableStateOf(false) }

    val allFilesPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            scope.launch {
                snackbar.show(resources.getString(R.string.script_storage_permission_desc))
            }
        }
    }

    fun showMessage(message: String) {
        scope.launch { snackbar.show(message) }
    }

    fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                showMessage(resources.getString(R.string.script_storage_permission_desc))
            }
        } else {
            allFilesPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    fun withStorageAccess(action: () -> Unit) {
        if (SystemPermissionChecks.hasAllFilesAccess(context)) {
            action()
        } else {
            showStoragePermission = true
        }
    }

    @Suppress("DEPRECATION")
    fun recommendedStorageRoot(): String =
        File(Environment.getExternalStorageDirectory(), "IzukiJS").absolutePath

    fun requestStorageSwitch(path: String?) {
        pendingStorageRoot = path
        copyStorageScripts = true
        showStorageSwitch = true
    }

    fun performStorageSwitch(path: String?, copy: Boolean) {
        showStorageSwitch = false
        switchingStorage = true
        scope.launch {
            try {
                when (val result = viewModel.switchScriptStorage(path, copy)) {
                    ScriptStorageSwitchResult.Unchanged ->
                        snackbar.show(resources.getString(R.string.script_storage_unchanged))

                    ScriptStorageSwitchResult.Running ->
                        snackbar.show(resources.getString(R.string.script_storage_running))

                    ScriptStorageSwitchResult.InvalidPath ->
                        snackbar.show(resources.getString(R.string.script_storage_invalid))

                    ScriptStorageSwitchResult.NestedRoot ->
                        snackbar.show(resources.getString(R.string.script_storage_nested))

                    is ScriptStorageSwitchResult.NotWritable -> {
                        snackbar.show(
                            resources.getString(
                                R.string.script_storage_not_writable,
                                result.path,
                            ),
                        )
                        if (!SystemPermissionChecks.hasAllFilesAccess(context)) {
                            showStoragePermission = true
                        }
                    }

                    is ScriptStorageSwitchResult.Success -> {
                        val message = if (result.copied == 0 && result.skipped == 0 &&
                            result.failed == 0
                        ) {
                            resources.getString(R.string.script_storage_switch_result_plain)
                        } else {
                            resources.getString(
                                R.string.script_storage_switch_result,
                                result.copied,
                                result.skipped,
                                result.failed,
                            )
                        }
                        snackbar.show(message)
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                snackbar.show(resources.getString(R.string.record_page_failed))
            } finally {
                switchingStorage = false
                pendingStorageRoot = null
            }
        }
    }

    fun copyStoragePath() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("IzukiJS", currentRoot.absolutePath))
        showMessage(resources.getString(R.string.script_storage_copied))
    }


    fun refreshLogSize() {
        scope.launch {
            logFileSize = viewModel.logFileSizeBytes()
        }
    }

    fun refreshCacheImages() {
        scope.launch {
            cacheImages = viewModel.cacheImageStats()
        }
    }

    /** 裁剪日志是磁盘 I/O，放到后台并在完成后刷新大小，避免阻塞主线程。 */
    fun trimLogsAndRefresh() {
        scope.launch {
            viewModel.trimLogs()
            logFileSize = viewModel.logFileSizeBytes()
        }
    }

    LaunchedEffect(Unit) {
        refreshLogSize()
        refreshCacheImages()
    }

    SectionCard(stringResource(R.string.script_storage_title)) {
        Text(
            stringResource(
                if (ui.scriptStorage.isDefault) {
                    R.string.script_storage_default
                } else {
                    R.string.script_storage_custom
                },
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            currentRoot.absolutePath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.script_storage_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ActionButtons {
            OutlinedButton(
                enabled = !busy && !switchingStorage && !ui.scriptRunning,
                onClick = { withStorageAccess { showStoragePicker = true } },
            ) { Text(stringResource(R.string.script_storage_choose)) }
            OutlinedButton(
                enabled = !busy && !switchingStorage && !ui.scriptRunning,
                onClick = {
                    withStorageAccess { requestStorageSwitch(recommendedStorageRoot()) }
                },
            ) { Text(stringResource(R.string.script_storage_recommended)) }
        }
        Spacer(Modifier.height(4.dp))
        ActionButtons {
            OutlinedButton(
                enabled = !busy && !switchingStorage && !ui.scriptRunning &&
                    !ui.scriptStorage.isDefault,
                onClick = { requestStorageSwitch(null) },
            ) { Text(stringResource(R.string.script_storage_reset)) }
            TextButton(onClick = { copyStoragePath() }) {
                Text(stringResource(R.string.script_storage_copy_path))
            }
        }
        if (ui.scriptRunning) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.script_storage_running),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    Spacer(Modifier.height(SectionGap))
    SectionCard(stringResource(R.string.storage_log_title)) {
        SwitchRow(
            label = stringResource(R.string.storage_auto_clean),
            checked = logSettings.autoClean,
        ) {
            viewModel.saveLogSettings(logSettings.copy(autoClean = it))
            // 关闭自动清理时应保留更多日志；只有重新开启时才立即裁剪一次。
            if (it) trimLogsAndRefresh() else refreshLogSize()
        }
        Spacer(Modifier.height(SectionGap))

        Text(stringResource(R.string.storage_log_level), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        ChipFlow {
            listOf(
                LogLevel.DEBUG to stringResource(R.string.log_level_all),
                LogLevel.INFO to stringResource(R.string.log_level_info),
                LogLevel.WARN to stringResource(R.string.log_level_warn),
                LogLevel.ERROR to stringResource(R.string.log_level_error),
            ).forEach { (level, label) ->
                FilterChip(
                    selected = logSettings.minLevel == level,
                    onClick = {
                        viewModel.saveLogSettings(logSettings.copy(minLevel = level))
                    },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(SectionGap))

        Text(stringResource(R.string.storage_max_size), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        ChipFlow {
            listOf(1, 5, 10, 20).forEach { size ->
                FilterChip(
                    selected = logSettings.maxSizeMb == size,
                    onClick = {
                        viewModel.saveLogSettings(logSettings.copy(maxSizeMb = size))
                        trimLogsAndRefresh()
                    },
                    label = { Text("$size MB") },
                )
            }
        }
        Spacer(Modifier.height(SectionGap))

        Text(stringResource(R.string.storage_retention_days), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        ChipFlow {
            listOf(1, 3, 7, 30).forEach { days ->
                FilterChip(
                    selected = logSettings.retentionDays == days,
                    onClick = {
                        viewModel.saveLogSettings(logSettings.copy(retentionDays = days))
                        trimLogsAndRefresh()
                    },
                    label = { Text(pluralStringResource(R.plurals.storage_days, days, days)) },
                )
            }
        }
        Spacer(Modifier.height(SectionGap))
        Text(
            stringResource(R.string.storage_usage, formatFileSize(logFileSize)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ActionButtons {
            OutlinedButton(
                onClick = { confirmClearLog = true },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.storage_clear_now))
            }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val file = newCacheExportFile(context, "izukijs_log", "log")
                            val ok = viewModel.exportLogs(file)
                            if (ok) {
                                shareFile(
                                    context = context,
                                    file = file,
                                    chooserTitle = resources.getString(
                                        R.string.storage_export_log_chooser,
                                    ),
                                )
                            } else {
                                runCatching { file.delete() }
                                snackbar.show(resources.getString(R.string.record_page_failed))
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.storage_export_log)) }
        }
    }

    Spacer(Modifier.height(SectionGap))
    SectionCard(stringResource(R.string.storage_cache_images_title)) {
        Text(
            stringResource(R.string.storage_cache_images_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                R.string.storage_cache_images_usage,
                cacheImages.count,
                formatFileSize(cacheImages.bytes),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ActionButtons {
            OutlinedButton(
                enabled = cacheImages.count > 0,
                onClick = { confirmClearCacheImages = true },
            ) {
                Text(stringResource(R.string.storage_cache_images_clear))
            }
        }
    }

    Spacer(Modifier.height(SectionGap))
    SectionCard(stringResource(R.string.settings_perf_baseline)) {
        Text(
            stringResource(R.string.settings_perf_baseline_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            label = stringResource(R.string.settings_perf_baseline_toggle),
            description = stringResource(R.string.settings_perf_baseline_toggle_desc),
            checked = ui.runtimeSettings.perfBaseline,
        ) {
            viewModel.saveRuntimeSettings(ui.runtimeSettings.copy(perfBaseline = it))
        }
        if (ui.runtimeSettings.perfBaseline) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { PerfMetrics.reportNow() }) {
                Text(stringResource(R.string.settings_perf_baseline_report_now))
            }
        }
    }

    if (confirmClearLog) {
        ConfirmDialog(
            title = stringResource(R.string.storage_clear_now),
            message = stringResource(R.string.storage_clear_confirm),
            destructive = true,
            onDismiss = { confirmClearLog = false },
            onConfirm = {
                confirmClearLog = false
                busy = true
                scope.launch {
                    try {
                        viewModel.clearLogs()
                        logFileSize = viewModel.logFileSizeBytes()
                        snackbar.show(resources.getString(R.string.storage_log_cleared))
                    } catch (t: Throwable) {
                        snackbar.show(resources.getString(R.string.record_page_failed))
                    } finally {
                        busy = false
                    }
                }
            },
        )
    }

    if (confirmClearCacheImages) {
        ConfirmDialog(
            title = stringResource(R.string.storage_cache_images_clear),
            message = stringResource(R.string.storage_cache_images_confirm),
            destructive = true,
            onDismiss = { confirmClearCacheImages = false },
            onConfirm = {
                confirmClearCacheImages = false
                scope.launch {
                    viewModel.clearCacheImages()
                    cacheImages = viewModel.cacheImageStats()
                }
            },
        )
    }

    if (showStoragePicker) {
        ScriptDirectoryPicker(
            // 默认目录位于应用私有路径，直接打开无法浏览共享存储；从推荐目录开始更实用。
            initialPath = if (ui.scriptStorage.isDefault) {
                recommendedStorageRoot()
            } else {
                currentRoot.absolutePath
            },
            onDismiss = { showStoragePicker = false },
            onConfirm = { path ->
                showStoragePicker = false
                requestStorageSwitch(path)
            },
        )
    }

    if (showStorageSwitch) {
        val targetRoot = remember(pendingStorageRoot) {
            if (pendingStorageRoot.isNullOrBlank()) {
                context.filesDir
            } else {
                File(pendingStorageRoot!!)
            }
        }
        val preview = remember(targetRoot) {
            buildString {
                appendLine(ScriptStoragePaths.scriptsDir(targetRoot).absolutePath)
                append(ScriptStoragePaths.projectsDir(targetRoot).absolutePath)
            }
        }
        AlertDialog(
            onDismissRequest = { showStorageSwitch = false },
            title = { Text(stringResource(R.string.script_storage_switch_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.script_storage_switch_message, preview),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = copyStorageScripts,
                            onCheckedChange = { copyStorageScripts = it },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.script_storage_switch_copy),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !switchingStorage,
                    onClick = { performStorageSwitch(pendingStorageRoot, copyStorageScripts) },
                ) { Text(stringResource(R.string.script_storage_switch_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showStorageSwitch = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showStoragePermission) {
        AlertDialog(
            onDismissRequest = { showStoragePermission = false },
            title = { Text(stringResource(R.string.script_storage_permission_title)) },
            text = { Text(stringResource(R.string.script_storage_permission_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStoragePermission = false
                        requestAllFilesAccess()
                    },
                ) { Text(stringResource(R.string.script_storage_permission_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showStoragePermission = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
