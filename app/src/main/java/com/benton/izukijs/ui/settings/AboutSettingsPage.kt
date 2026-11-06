package com.benton.izukijs.ui.settings

import com.benton.izukijs.ui.common.SectionGap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.BuildConfig
import com.benton.izukijs.R
import com.benton.izukijs.update.UpdateMethod
import com.benton.izukijs.update.UpdateSettings
import com.benton.izukijs.update.UpdateState
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SwitchRow
import com.benton.izukijs.ui.appViewModel

@Composable
internal fun AboutSettings() {
    SectionCard(stringResource(R.string.settings_about)) {
        Text(stringResource(R.string.about_name), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.about_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(SectionGap))
    UpdateSettingsSection()
}

@Composable
private fun UpdateSettingsSection() {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = ui.updateSettings
    val state = ui.updateState

    fun update(new: UpdateSettings) = viewModel.saveUpdateSettings(new)

    SectionCard(stringResource(R.string.update_section_title)) {
        Text(
            stringResource(R.string.update_current_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        UpdateStatusLine(state)
        Spacer(Modifier.height(SectionGap))

        val busy = state is UpdateState.Checking || state is UpdateState.Downloading
        val info = (state as? UpdateState.Available)?.info
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.checkForUpdates() },
                enabled = !busy,
            ) { Text(stringResource(R.string.update_check_button)) }

            if (info != null) {
                val inApp = settings.method == UpdateMethod.IN_APP && !info.apkUrl.isNullOrBlank()
                OutlinedButton(
                    onClick = {
                        if (inApp) {
                            viewModel.downloadAndInstallUpdate()
                        } else {
                            viewModel.openUpdateOnGitHub()
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (inApp) R.string.update_download else R.string.update_open_github,
                        ),
                    )
                }
            }

            if (state is UpdateState.Downloaded) {
                OutlinedButton(onClick = { viewModel.installDownloadedUpdate() }) {
                    Text(stringResource(R.string.update_install))
                }
            }
        }

        if (info != null && settings.method == UpdateMethod.IN_APP && info.apkUrl.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.update_no_asset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(SectionGap))
        SwitchRow(
            label = stringResource(R.string.update_auto_check),
            description = stringResource(R.string.update_auto_check_desc),
            checked = settings.autoCheck,
        ) { update(settings.copy(autoCheck = it)) }

        Spacer(Modifier.height(SectionGap))
        Text(stringResource(R.string.update_method), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        ChipFlow {
            UpdateMethod.entries.forEach { method ->
                FilterChip(
                    selected = settings.method == method,
                    onClick = { update(settings.copy(method = method)) },
                    label = {
                        Text(
                            stringResource(
                                when (method) {
                                    UpdateMethod.IN_APP -> R.string.update_method_in_app
                                    UpdateMethod.GITHUB -> R.string.update_method_github
                                },
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun UpdateStatusLine(state: UpdateState) {
    when (state) {
        UpdateState.Idle -> Unit

        UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.update_checking),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        UpdateState.UpToDate -> Text(
            stringResource(R.string.update_up_to_date),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is UpdateState.Available -> Text(
            stringResource(R.string.update_available, state.info.versionName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        is UpdateState.Downloading -> Column {
            Text(
                if (state.progress < 0) {
                    stringResource(R.string.update_downloading_unknown)
                } else {
                    stringResource(R.string.update_downloading, state.progress)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            if (state.progress < 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        is UpdateState.Downloaded -> Text(
            stringResource(R.string.update_downloaded),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is UpdateState.CheckFailed -> Text(
            stringResource(R.string.update_check_failed, state.message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        is UpdateState.DownloadFailed -> Text(
            stringResource(R.string.update_download_failed, state.message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
