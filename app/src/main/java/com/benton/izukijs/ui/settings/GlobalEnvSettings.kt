package com.benton.izukijs.ui.settings

import androidx.activity.compose.BackHandler
import com.benton.izukijs.ui.common.SectionGap
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.model.EnvParseResult
import com.benton.izukijs.model.EnvText
import com.benton.izukijs.ui.common.ConfirmDialog
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.launch

/**
 * 全局环境变量编辑。所有脚本默认共享；脚本内同名变量优先。
 */
@Composable
fun GlobalEnvSettings(onBack: () -> Unit = {}) {
    val container = rememberAppContainer()
    val snackbar = LocalSnackbarController.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val stored by container.globalEnvRepository.values.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(EnvText.format(stored)) }
    var dirty by rememberSaveable { mutableStateOf(false) }
    var errorLine by rememberSaveable { mutableStateOf<Int?>(null) }
    var showDiscard by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    // 备份导入 / 重置会改写仓库值；未编辑时跟随刷新，避免旧文本在保存时覆盖新配置。
    LaunchedEffect(stored) {
        if (!dirty) text = EnvText.format(stored)
    }

    fun commit(values: Map<String, String>) {
        container.globalEnvRepository.save(values)
        errorLine = null
        dirty = false
        scope.launch { snackbar.show(resources.getString(R.string.settings_env_saved)) }
    }

    BackHandler(enabled = dirty) { showDiscard = true }

    SectionCard(stringResource(R.string.settings_env)) {
        Text(
            stringResource(R.string.settings_env_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(SectionGap))
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                dirty = true
                errorLine = null
            },
            label = { Text(stringResource(R.string.settings_env_placeholder)) },
            placeholder = { Text(stringResource(R.string.env_example)) },
            isError = errorLine != null,
            supportingText = {
                val line = errorLine
                if (line != null) {
                    Text(stringResource(R.string.env_invalid_line, line))
                } else {
                    Text(stringResource(R.string.env_hint))
                }
            },
            minLines = 6,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(SectionGap))
        Button(
            enabled = dirty,
            onClick = {
                when (val result = EnvText.parse(text)) {
                    is EnvParseResult.Failure -> errorLine = result.lineNumber
                    is EnvParseResult.Success -> {
                        if (result.values.isEmpty() && stored.isNotEmpty()) {
                            showClearConfirm = true
                        } else {
                            commit(result.values)
                        }
                    }
                }
            },
        ) {
            Text(stringResource(R.string.common_save))
        }
    }

    if (showDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.script_edit_discard_title),
            message = stringResource(R.string.script_edit_discard_message),
            confirmLabel = stringResource(R.string.script_edit_discard),
            destructive = true,
            onConfirm = {
                showDiscard = false
                onBack()
            },
            onDismiss = { showDiscard = false },
        )
    }

    if (showClearConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.env_clear_confirm_title),
            message = stringResource(R.string.env_clear_confirm_message),
            destructive = true,
            onConfirm = {
                showClearConfirm = false
                commit(emptyMap())
            },
            onDismiss = { showClearConfirm = false },
        )
    }
}
