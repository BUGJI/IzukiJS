package com.benton.izukijs.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.model.EnvParseResult
import com.benton.izukijs.model.EnvText
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.launch

/**
 * 全局环境变量编辑。所有脚本默认共享；脚本内同名变量优先。
 */
@Composable
fun GlobalEnvSettings() {
    val container = rememberAppContainer()
    val snackbar = LocalSnackbarController.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val stored by container.globalEnvRepository.values.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf(EnvText.format(stored)) }
    var errorLine by remember { mutableStateOf<Int?>(null) }

    SectionCard(stringResource(R.string.settings_env)) {
        Text(
            stringResource(R.string.settings_env_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
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
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                when (val result = EnvText.parse(text)) {
                    is EnvParseResult.Failure -> errorLine = result.lineNumber
                    is EnvParseResult.Success -> {
                        container.globalEnvRepository.save(result.values)
                        errorLine = null
                        scope.launch { snackbar.show(resources.getString(R.string.settings_env_saved)) }
                    }
                }
            },
        ) {
            Text(stringResource(R.string.common_save))
        }
    }
}
