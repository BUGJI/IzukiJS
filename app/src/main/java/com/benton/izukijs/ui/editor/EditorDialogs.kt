package com.benton.izukijs.ui.editor


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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.benton.izukijs.R
import com.benton.izukijs.data.ScriptVersion
import com.benton.izukijs.ui.common.formatDateTime

@Composable
internal fun EditorVariablesDialog(
    env: Map<String, String>,
    state: Map<String, String>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_variables)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                item {
                    Text(
                        stringResource(R.string.editor_variables_env),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (env.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.editor_variables_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(env.entries.toList()) { entry ->
                        KeyValueRow(entry.key, entry.value)
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    Text(
                        stringResource(R.string.editor_variables_state),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (state.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.editor_variables_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(state.entries.toList()) { entry ->
                        KeyValueRow(entry.key, entry.value)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
    )
}

@Composable
internal fun EditorHistoryDialog(
    history: EditorHistoryState,
    onDismiss: () -> Unit,
    onRestore: (ScriptVersion) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_history)) },
        text = {
            when {
                history.loading -> Text(stringResource(R.string.editor_history_loading))
                history.entries.isEmpty() -> Text(stringResource(R.string.editor_history_empty))
                else -> LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(history.entries, key = { it.first.timestamp }) { (version, preview) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    formatDateTime(version.timestamp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    preview.ifBlank { stringResource(R.string.editor_history_blank) },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { onRestore(version) }) {
                                Text(stringResource(R.string.editor_history_restore))
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
    )
}

@Composable
private fun KeyValueRow(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(120.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
