package com.benton.izukijs.ui.settings

import com.benton.izukijs.ui.common.SectionGap
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import com.benton.izukijs.runtime.CacheImageStore
import com.benton.izukijs.ui.common.ActionButtons
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.ConfirmDialog
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SwitchRow
import com.benton.izukijs.ui.common.formatFileSize
import com.benton.izukijs.ui.common.newCacheExportFile
import com.benton.izukijs.ui.common.shareFile
import com.benton.izukijs.ui.appViewModel
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
}
