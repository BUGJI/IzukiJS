package com.benton.izukijs.ui.settings

import com.benton.izukijs.ui.common.SectionGap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.benton.izukijs.R
import com.benton.izukijs.ui.common.ConfirmDialog
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.newCacheExportFile
import com.benton.izukijs.ui.common.shareFile
import com.benton.izukijs.ui.appViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupSettingsScreen() {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarController.current
    var confirmReset by remember { mutableStateOf(false) }
    var pendingImportText by rememberSaveable { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                snackbar.show(resources.getString(R.string.backup_read_failed))
            } else {
                pendingImportText = text
            }
        }
    }

    SectionCard(stringResource(R.string.settings_backup)) {
        Text(
            stringResource(R.string.backup_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(SectionGap))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    val file = newCacheExportFile(context, "izukijs_config", "json")
                    val ok = viewModel.exportConfig(file)
                    if (ok) {
                        shareFile(
                            context = context,
                            file = file,
                            chooserTitle = resources.getString(R.string.storage_export_log_chooser),
                            subject = file.name,
                        )
                    } else {
                        snackbar.show(resources.getString(R.string.backup_export_failed))
                    }
                }
            }) { Text(stringResource(R.string.backup_export)) }
            OutlinedButton(onClick = {
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }) { Text(stringResource(R.string.backup_import)) }
        }
    }

    Spacer(Modifier.height(SectionGap))
    SectionCard(stringResource(R.string.backup_reset_title)) {
        Text(
            stringResource(R.string.backup_reset_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.backup_reset_button)) }
    }

    pendingImportText?.let { text ->
        AlertDialog(
            onDismissRequest = { pendingImportText = null },
            title = { Text(stringResource(R.string.backup_import)) },
            text = { Text(stringResource(R.string.backup_import_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingImportText = null
                    scope.launch {
                        val msg = runCatching { viewModel.importConfig(text) }
                            .fold(
                                onSuccess = { resources.getString(R.string.backup_import_success) },
                                onFailure = {
                                    resources.getString(R.string.backup_import_failed, it.message.orEmpty())
                                },
                            )
                        snackbar.show(msg)
                    }
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportText = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.backup_reset_button),
            message = stringResource(R.string.backup_reset_confirm),
            destructive = true,
            onDismiss = { confirmReset = false },
            onConfirm = {
                runCatching { viewModel.resetConfig() }
                    .onSuccess { scope.launch { snackbar.show(resources.getString(R.string.backup_reset_done)) } }
                    .onFailure { scope.launch { snackbar.show(resources.getString(R.string.backup_reset_failed)) } }
                confirmReset = false
            },
        )
    }
}
