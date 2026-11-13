package com.benton.izukijs.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.benton.izukijs.R
import com.benton.izukijs.model.EnvField

private val EnvValuesSaver = listSaver<Map<String, String>, String>(
    save = { values -> values.flatMap { listOf(it.key, it.value) } },
    restore = { flat ->
        flat.chunked(2).mapNotNull { pair ->
            if (pair.size == 2) pair[0] to pair[1] else null
        }.toMap()
    },
)

/**
 * 运行前写入脚本运行参数。字段来自脚本头部 `// @env` 声明，[initial] 为上次保存值或默认值。
 */
@Composable
fun ScriptEnvDialog(
    scriptName: String,
    fields: List<EnvField>,
    initial: Map<String, String>,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, String>) -> Unit,
) {
    var values by rememberSaveable(
        fields.joinToString("|") { it.key },
        stateSaver = EnvValuesSaver,
    ) {
        mutableStateOf(
            fields.associate { it.key to (initial[it.key] ?: it.default) },
        )
    }
    // 打开时自动聚焦首个字段，减少一次点击（节点未就绪时忽略）。
    val firstFocus = remember(fields) { FocusRequester() }
    LaunchedEffect(fields) { runCatching { firstFocus.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.env_title, scriptName)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                fields.forEachIndexed { index, field ->
                    OutlinedTextField(
                        value = values[field.key].orEmpty(),
                        onValueChange = { values = values + (field.key to it) },
                        singleLine = true,
                        label = { Text(field.label.ifBlank { field.key }) },
                        placeholder = field.default.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                        supportingText = if (field.label.isBlank()) null else {
                            { Text(field.key) }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(fields.associate { it.key to values[it.key].orEmpty() })
                },
            ) { Text(stringResource(R.string.common_run)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
