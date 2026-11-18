package com.benton.izukijs.ui.scripts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.benton.izukijs.R
import com.benton.izukijs.ui.common.ConfirmDialog

internal const val BATCH_TAG_ADD = "add"
internal const val BATCH_TAG_REMOVE = "remove"

@Composable
internal fun NewScriptDialog(
    name: String,
    onNameChange: (String) -> Unit,
    submitting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scripts_new)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                singleLine = true,
                label = { Text(stringResource(R.string.scripts_name)) },
                isError = name.isBlank(),
                supportingText = if (name.isBlank()) {
                    { Text(stringResource(R.string.script_edit_name_required)) }
                } else {
                    null
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !submitting && name.isNotBlank(),
            ) { Text(stringResource(R.string.scripts_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
internal fun EditTagsDialog(
    tagInput: String,
    onTagInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scripts_edit_tags)) },
        text = {
            OutlinedTextField(
                value = tagInput,
                onValueChange = onTagInputChange,
                singleLine = true,
                label = { Text(stringResource(R.string.scripts_tags_label)) },
                placeholder = { Text(stringResource(R.string.scripts_tags_placeholder)) },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
internal fun DeleteScriptDialog(
    scriptName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scripts_delete_title)) },
        text = { Text(stringResource(R.string.scripts_delete_message, scriptName)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(stringResource(R.string.common_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
internal fun BatchTagDialog(
    mode: String,
    onModeChange: (String) -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (mode == BATCH_TAG_REMOVE) {
                        R.string.scripts_batch_remove_tags
                    } else {
                        R.string.scripts_batch_add_tags
                    },
                ),
            )
        },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = mode == BATCH_TAG_ADD,
                        onClick = { onModeChange(BATCH_TAG_ADD) },
                        label = { Text(stringResource(R.string.scripts_tags_add)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = mode == BATCH_TAG_REMOVE,
                        onClick = { onModeChange(BATCH_TAG_REMOVE) },
                        label = { Text(stringResource(R.string.scripts_tags_remove)) },
                    )
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    singleLine = true,
                    label = { Text(stringResource(R.string.scripts_tags_label)) },
                    placeholder = { Text(stringResource(R.string.scripts_tags_placeholder)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
internal fun BatchDeleteDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.scripts_delete_title),
        message = stringResource(R.string.scripts_batch_delete_message, count),
        destructive = true,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}
