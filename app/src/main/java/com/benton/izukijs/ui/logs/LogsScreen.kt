package com.benton.izukijs.ui.logs

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.ui.common.IzukiPageScaffold
import com.benton.izukijs.ui.console.ConsolePanel
import com.benton.izukijs.ui.rememberAppContainer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen() {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logs by container.logBus.entries.collectAsStateWithLifecycle()

    IzukiPageScaffold(
        title = { Text(stringResource(R.string.nav_logs)) },
        actions = {
            IconButton(
                onClick = {
                    scope.launch {
                        val file = File(
                            File(context.cacheDir, "exports"),
                            "izukijs_log_${System.currentTimeMillis()}.log",
                        )
                        val ok = withContext(Dispatchers.IO) { container.logFileStore.exportTo(file) }
                        if (ok) shareFile(context, file)
                    }
                },
            ) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.logs_export))
            }
        },
    ) { padding ->
        ConsolePanel(
            entries = logs,
            onClear = { container.logBus.clear() },
            showHeader = false,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

private fun shareFile(context: android.content.Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.logs_export))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
