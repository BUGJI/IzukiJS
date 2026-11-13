package com.benton.izukijs.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.BuildConfig
import com.benton.izukijs.R
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.rememberAppContainer
import com.benton.izukijs.update.UpdateInfo
import com.benton.izukijs.update.UpdateMethod
import com.benton.izukijs.update.UpdateState

@Composable
fun UpdatePromptHost() {
    val container = rememberAppContainer()
    val snackbar = LocalSnackbarController.current
    val info by container.updateManager.prompt.collectAsStateWithLifecycle()
    val settings by container.updateSettingsRepository.settings.collectAsStateWithLifecycle()
    val state by container.updateManager.state.collectAsStateWithLifecycle()
    var progressDismissed by remember { mutableStateOf(false) }

    val downloadedMessage = stringResource(R.string.update_downloaded)
    val failedMessage = stringResource(R.string.update_download_failed_short)
    val installLabel = stringResource(R.string.update_install)
    val retryLabel = stringResource(R.string.update_retry)

    LaunchedEffect(state) {
        if (state !is UpdateState.Downloading) progressDismissed = false
        when (state) {
            is UpdateState.Downloaded -> {
                val result = snackbar.show(
                    message = downloadedMessage,
                    actionLabel = installLabel,
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    container.updateManager.installDownloaded()
                }
            }

            is UpdateState.DownloadFailed -> {
                val result = snackbar.show(
                    message = failedMessage,
                    actionLabel = retryLabel,
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    container.updateManager.retry()
                }
            }

            else -> Unit
        }
    }

    val current = info
    if (current != null) {
        UpdateAvailableDialog(
            info = current,
            method = settings.method,
            onConfirm = {
                if (settings.method == UpdateMethod.IN_APP && !current.apkUrl.isNullOrBlank()) {
                    container.updateManager.downloadAndInstall()
                } else {
                    container.updateManager.openOnGitHub()
                }
            },
            onDismiss = { container.updateManager.dismissPrompt() },
        )
    }

    val downloading = state as? UpdateState.Downloading
    if (downloading != null && !progressDismissed) {
        UpdateDownloadProgressDialog(
            progress = downloading.progress,
            onDismiss = { progressDismissed = true },
        )
    }
}

@Composable
private fun UpdateDownloadProgressDialog(progress: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_downloading_title)) },
        text = {
            Column {
                if (progress < 0) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (progress < 0) {
                        stringResource(R.string.update_downloading_unknown)
                    } else {
                        stringResource(R.string.update_downloading, progress)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_background)) }
        },
    )
}

@Composable
fun UpdateAvailableDialog(
    info: UpdateInfo,
    method: UpdateMethod,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val useInApp = method == UpdateMethod.IN_APP && !info.apkUrl.isNullOrBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_dialog_title, info.versionName)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.update_current_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    info.changelog.ifBlank { stringResource(R.string.update_dialog_no_changelog) },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(
                    stringResource(
                        if (useInApp) R.string.update_download else R.string.update_open_github,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
        },
    )
}
