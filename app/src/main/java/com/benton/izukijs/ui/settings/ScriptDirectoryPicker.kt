package com.benton.izukijs.ui.settings

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.benton.izukijs.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于 `java.io.File` 的目录选择器。
 *
 * 脚本仓库与 `require()` 都依赖真实文件路径，因此这里不用 SAF 的 `content://`
 * 目录授权。未授予「全部文件访问」时共享存储可能列不出内容，调用方应先引导授权。
 */
@Composable
internal fun ScriptDirectoryPicker(
    initialPath: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val start = remember(initialPath) { resolveStart(initialPath) }
    var current by remember(start) { mutableStateOf(start) }
    var manual by remember(start) { mutableStateOf(start.absolutePath) }
    var error by remember(start) { mutableStateOf(false) }

    // 目录可能很大（例如共享存储根目录），列表放到 IO 线程读取，避免阻塞组合。
    var children by remember(current) { mutableStateOf<List<File>?>(null) }
    LaunchedEffect(current) {
        children = null
        children = withContext(Dispatchers.IO) {
            current.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
        }
    }

    fun navigateTo(file: File) {
        if (file.isDirectory) {
            current = file
            manual = file.absolutePath
            error = false
        } else {
            error = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.script_storage_picker_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    current.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = manual,
                    onValueChange = {
                        manual = it
                        error = false
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.script_storage_picker_manual)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { navigateTo(File(manual.trim())) },
                    enabled = manual.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.script_storage_picker_go)) }
                if (error) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.script_storage_picker_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = current.parentFile != null) {
                                    current.parentFile?.let { navigateTo(it) }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.script_storage_picker_up))
                        }
                    }
                    when (val dirs = children) {
                        null -> item {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(vertical = 8.dp),
                                strokeWidth = 2.dp,
                            )
                        }

                        else -> if (dirs.isEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.script_storage_picker_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                        } else {
                            items(dirs, key = { it.absolutePath }) { child ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { navigateTo(child) }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.Folder, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        child.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(current.absolutePath) }) {
                Text(stringResource(R.string.script_storage_picker_select))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Suppress("DEPRECATION")
private fun resolveStart(path: String): File {
    val candidate = runCatching { File(path).canonicalFile }.getOrNull()
    if (candidate?.isDirectory == true) return candidate
    val external = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
    return external?.takeIf { it.isDirectory } ?: File("/")
}
