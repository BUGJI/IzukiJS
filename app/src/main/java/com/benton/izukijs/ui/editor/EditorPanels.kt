package com.benton.izukijs.ui.editor


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.benton.izukijs.R
import com.benton.izukijs.core.log.LogEntry
import com.benton.izukijs.ui.console.ConsolePanel

@Composable
internal fun FindReplacePanel(
    query: String,
    queryFocus: FocusRequester,
    onQueryChange: (String) -> Unit,
    replacement: String,
    onReplacementChange: (String) -> Unit,
    matchCount: Int,
    activePosition: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onClose()
                        true
                    } else {
                        false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    modifier = Modifier.weight(1f).focusRequester(queryFocus),
                    label = { Text(stringResource(R.string.editor_find)) },
                    placeholder = { Text(stringResource(R.string.editor_find_placeholder)) },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (matchCount == 0) {
                        stringResource(R.string.editor_match_none)
                    } else {
                        stringResource(R.string.editor_match_of, activePosition, matchCount)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onPrev, enabled = matchCount > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.editor_prev_match))
                }
                IconButton(onClick = onNext, enabled = matchCount > 0) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.editor_next_match))
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.editor_close_find))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = replacement,
                    onValueChange = onReplacementChange,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.editor_replace)) },
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onReplace, enabled = matchCount > 0) {
                    Text(stringResource(R.string.editor_replace_one))
                }
                TextButton(onClick = onReplaceAll, enabled = matchCount > 0) {
                    Text(stringResource(R.string.editor_replace_all))
                }
            }
        }
    }
}

/** 单独读取日志条数，使日志刷新只重组这个徽标，不牵连编辑器本体。 */
@Composable
internal fun LogCountBadge(logsState: State<List<LogEntry>>) {
    val count = logsState.value.size
    if (count > 0) {
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.editor_console_count, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 在子作用域内解包日志列表，同样把重组范围限制在控制台面板内。 */
@Composable
internal fun EditorConsolePanel(
    logsState: State<List<LogEntry>>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ConsolePanel(
        entries = logsState.value,
        onClear = onClear,
        showHeader = false,
        modifier = modifier,
    )
}
