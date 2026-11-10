package com.benton.izukijs.ui.settings

import com.benton.izukijs.ui.common.SectionGap
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.core.log.LogLevel
import com.benton.izukijs.core.perf.PerfMetrics
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.ConfirmDialog
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
    var confirmClearLog by remember { mutableStateOf(false) }

    fun refreshLogSize() {
        scope.launch {
            logFileSize = viewModel.logFileSizeBytes()
        }
    }

    /** 裁剪日志是磁盘 I/O，放到后台并在完成后刷新大小，避免阻塞主线程。 */
    fun trimLogsAndRefresh() {
        scope.launch {
            viewModel.trimLogs()
            logFileSize = viewModel.logFileSizeBytes()
        }
    }

    LaunchedEffect(Unit) { refreshLogSize() }

    SectionCard(stringResource(R.string.storage_log_title)) {
        SwitchRow(
            label = stringResource(R.string.storage_auto_clean),
            checked = logSettings.autoClean,
        ) {
            viewModel.saveLogSettings(logSettings.copy(autoClean = it))
            trimLogsAndRefresh()
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
                    label = { Text(stringResource(R.string.storage_days, days)) },
                )
            }
        }
        Spacer(Modifier.height(SectionGap))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.storage_usage, formatFileSize(logFileSize)),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { confirmClearLog = true }) {
                Text(stringResource(R.string.storage_clear_now))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    val file = newCacheExportFile(context, "izukijs_log", "log")
                    val ok = viewModel.exportLogs(file)
                    if (ok) {
                        shareFile(
                            context = context,
                            file = file,
                            chooserTitle = resources.getString(R.string.storage_export_log_chooser),
                        )
                    }
                }
            }) { Text(stringResource(R.string.storage_export_log)) }
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
                scope.launch {
                    viewModel.clearLogs()
                    logFileSize = viewModel.logFileSizeBytes()
                }
                confirmClearLog = false
            },
        )
    }
}
