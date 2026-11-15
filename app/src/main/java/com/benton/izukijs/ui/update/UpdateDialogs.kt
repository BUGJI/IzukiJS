package com.benton.izukijs.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
    var permissionDismissed by remember { mutableStateOf(false) }

    val failedMessage = stringResource(R.string.update_download_failed_short)
    val retryLabel = stringResource(R.string.update_retry)
    val installPendingMessage = stringResource(R.string.update_install_pending)
    val permissionMessage = stringResource(R.string.update_need_install_permission)
    val permissionAction = stringResource(R.string.update_open_permission_settings)
    val laterLabel = stringResource(R.string.update_later)
    val downloadFailure = when (val current = state) {
        is UpdateState.DownloadFailed -> updateFailureMessage(current.failure)
        else -> null
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        container.updateManager.retryInstallAfterPermission()
    }

    LaunchedEffect(state) {
        if (state !is UpdateState.Downloading) progressDismissed = false
        if (state !is UpdateState.InstallPermissionRequired) permissionDismissed = false
        when (state) {
            is UpdateState.DownloadFailed -> {
                val result = snackbar.show(
                    message = downloadFailure ?: failedMessage,
                    actionLabel = retryLabel,
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    container.updateManager.retry()
                }
            }

            is UpdateState.InstallPending -> snackbar.show(installPendingMessage)

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
            onBackground = { progressDismissed = true },
            onCancel = { container.updateManager.cancelDownload() },
        )
    }

    if (state is UpdateState.InstallPermissionRequired && !permissionDismissed) {
        AlertDialog(
            onDismissRequest = { permissionDismissed = true },
            title = { Text(stringResource(R.string.update_install_permission_title)) },
            text = { Text(permissionMessage) },
            confirmButton = {
                TextButton(
                    onClick = {
                        permissionDismissed = true
                        container.updateManager.openInstallPermissionSettings()
                    },
                ) { Text(permissionAction) }
            },
            dismissButton = {
                TextButton(onClick = { permissionDismissed = true }) { Text(laterLabel) }
            },
        )
    }
}

@Composable
private fun UpdateDownloadProgressDialog(
    progress: Int,
    onBackground: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onBackground,
        title = { Text(stringResource(R.string.update_downloading_title)) },
        text = { UpdateProgress(progress) },
        confirmButton = {
            TextButton(onClick = onBackground) { Text(stringResource(R.string.update_background)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
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
                ReleaseNotesContent(info.changelog)
                if (info.apkSizeBytes > 0L) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.update_apk_size, formatUpdateSize(info.apkSizeBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
