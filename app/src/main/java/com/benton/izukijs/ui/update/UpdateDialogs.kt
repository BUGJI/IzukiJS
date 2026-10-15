package com.benton.izukijs.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.BuildConfig
import com.benton.izukijs.R
import com.benton.izukijs.ui.rememberAppContainer
import com.benton.izukijs.update.UpdateInfo
import com.benton.izukijs.update.UpdateMethod

@Composable
fun UpdatePromptHost() {
    val container = rememberAppContainer()
    val info by container.updateManager.prompt.collectAsStateWithLifecycle()
    val settings by container.updateSettingsRepository.settings.collectAsStateWithLifecycle()
    val current = info ?: return

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
