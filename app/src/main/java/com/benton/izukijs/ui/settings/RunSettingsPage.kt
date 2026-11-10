package com.benton.izukijs.ui.settings

import com.benton.izukijs.ui.common.SectionGap
import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.core.perf.PerfMetrics
import com.benton.izukijs.service.ScriptTileService
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SwitchRow
import com.benton.izukijs.ui.appViewModel
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 脚本运行：超时、唤醒、通知、快捷磁贴与性能基线。 */
@Composable
internal fun RunSettings() {
    TimeoutSettingsSection()

    Spacer(Modifier.height(SectionGap))
    WakeSettingsSection()

    Spacer(Modifier.height(SectionGap))
    NotificationSettingsSection()

    Spacer(Modifier.height(SectionGap))
    TileSettingsSection()

    Spacer(Modifier.height(SectionGap))
    PerfBaselineSettingsSection()
}

@Composable
private fun TimeoutSettingsSection() {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = ui.runtimeSettings

    SectionCard(stringResource(R.string.perm_timeout_title)) {
        Text(
            stringResource(R.string.perm_timeout_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ChipFlow {
            listOf(0, 10, 30, 60, 120).forEach { minutes ->
                FilterChip(
                    selected = settings.scriptTimeoutMinutes == minutes,
                    onClick = {
                        viewModel.saveRuntimeSettings(settings.copy(scriptTimeoutMinutes = minutes))
                    },
                    label = {
                        Text(
                            if (minutes == 0) {
                                stringResource(R.string.perm_timeout_unlimited)
                            } else {
                                stringResource(R.string.perm_timeout_minutes, minutes)
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun WakeSettingsSection() {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = ui.runtimeSettings

    SectionCard(stringResource(R.string.perm_wake_title)) {
        SwitchRow(
            label = stringResource(R.string.perm_wake_title),
            checked = settings.keepAwake,
            description = stringResource(R.string.perm_wake_desc),
        ) { viewModel.saveRuntimeSettings(settings.copy(keepAwake = it)) }
        Spacer(Modifier.height(12.dp))
        SwitchRow(
            label = stringResource(R.string.perm_wake_screen),
            checked = settings.wakeScreen,
            description = stringResource(R.string.perm_wake_screen_desc),
            enabled = settings.keepAwake,
        ) { viewModel.saveRuntimeSettings(settings.copy(wakeScreen = it)) }
    }
}

@Composable
private fun TileSettingsSection() {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val container = rememberAppContainer()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarController.current
    val settings = ui.runtimeSettings

    val pinnedId by container.tileSettingsRepository.scriptId.collectAsStateWithLifecycle()
    var pinnedName by remember(pinnedId) { mutableStateOf<String?>(null) }
    LaunchedEffect(pinnedId) {
        pinnedName = pinnedId?.let { id ->
            withContext(Dispatchers.IO) { container.scriptRepository.find(id)?.name }
        }
    }

    fun addTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = context.getSystemService(StatusBarManager::class.java)
        if (manager == null) {
            scope.launch { snackbar.show(resources.getString(R.string.perm_tile_add_failed)) }
            return
        }
        runCatching {
            manager.requestAddTileService(
                ComponentName(context, ScriptTileService::class.java),
                resources.getString(R.string.app_name),
                Icon.createWithResource(context, R.drawable.ic_stat_script),
                context.mainExecutor,
            ) { result ->
                val added =
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                        result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                scope.launch {
                    snackbar.show(
                        resources.getString(
                            if (added) R.string.perm_tile_added else R.string.perm_tile_add_failed,
                        ),
                    )
                }
            }
        }.onFailure {
            scope.launch { snackbar.show(resources.getString(R.string.perm_tile_add_failed)) }
        }
    }

    SectionCard(stringResource(R.string.perm_tile_title)) {
        Text(
            stringResource(R.string.perm_tile_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            label = stringResource(R.string.perm_tile_enable),
            checked = settings.tileEnabled,
        ) { viewModel.saveRuntimeSettings(settings.copy(tileEnabled = it)) }
        Spacer(Modifier.height(8.dp))
        Text(
            pinnedName?.let { stringResource(R.string.perm_tile_pinned, it) }
                ?: stringResource(R.string.perm_tile_pick_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            settings.tileEnabled &&
            pinnedName != null
        ) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = ::addTile) {
                Text(stringResource(R.string.perm_tile_add))
            }
        } else {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.perm_tile_manual_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PerfBaselineSettingsSection() {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
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
            onChange = {
                viewModel.saveRuntimeSettings(ui.runtimeSettings.copy(perfBaseline = it))
            },
        )
        if (ui.runtimeSettings.perfBaseline) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { PerfMetrics.reportNow() }) {
                Text(stringResource(R.string.settings_perf_baseline_report_now))
            }
        }
    }
}
