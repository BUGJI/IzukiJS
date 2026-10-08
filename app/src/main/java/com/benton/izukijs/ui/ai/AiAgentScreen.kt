package com.benton.izukijs.ui.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.ai.AiClient
import com.benton.izukijs.ai.AiConfig
import com.benton.izukijs.ai.AiPrompts
import com.benton.izukijs.ai.ChatMessage
import com.benton.izukijs.ui.common.DecimalField
import com.benton.izukijs.ui.common.LabeledField
import com.benton.izukijs.ui.common.NumberField
import com.benton.izukijs.ui.common.PageColumn
import com.benton.izukijs.ui.common.SecretField
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SwitchRow
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAgentScreen(onBack: () -> Unit) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by container.aiConfigRepository.config.collectAsStateWithLifecycle()

    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    fun update(new: AiConfig) = container.aiConfigRepository.save(new)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        PageColumn(
            modifier = Modifier.padding(padding).padding(16.dp),
        ) {
            SectionCard(stringResource(R.string.ai_master_title)) {
                SwitchRow(
                    label = stringResource(R.string.ai_enable),
                    checked = config.enabled,
                    description = stringResource(R.string.ai_enable_desc),
                ) {
                    update(config.copy(enabled = it))
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(stringResource(R.string.ai_api_title)) {
                Text(
                    stringResource(R.string.ai_api_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LabeledField(
                    label = stringResource(R.string.ai_base_url),
                    value = config.baseUrl,
                    placeholder = AiConfig.DEFAULT_BASE_URL,
                ) {
                    update(config.copy(baseUrl = it))
                }
                SecretField(stringResource(R.string.ocr_api_key), config.apiKey) { update(config.copy(apiKey = it)) }
                LabeledField(
                    label = stringResource(R.string.ai_model),
                    value = config.model,
                    placeholder = AiConfig.DEFAULT_MODEL,
                ) {
                    update(config.copy(model = it))
                }
                LabeledField(
                    label = stringResource(R.string.ai_custom_header_name),
                    value = config.extraHeaderName,
                    placeholder = stringResource(R.string.ai_custom_header_name_placeholder),
                ) { update(config.copy(extraHeaderName = it)) }
                SecretField(stringResource(R.string.ai_custom_header_value), config.extraHeaderValue) {
                    update(config.copy(extraHeaderValue = it))
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(stringResource(R.string.ai_params_title)) {
                SwitchRow(
                    label = stringResource(R.string.ai_stream),
                    checked = config.stream,
                    description = stringResource(R.string.ai_stream_desc),
                ) {
                    update(config.copy(stream = it))
                }
                Spacer(Modifier.height(8.dp))
                DecimalField(
                    label = stringResource(R.string.ai_temperature),
                    value = config.temperature,
                    range = 0.0..2.0,
                ) { update(config.copy(temperature = it)) }
                NumberField(
                    label = stringResource(R.string.ai_max_tokens),
                    value = config.maxTokens,
                    range = 1..32000,
                ) { update(config.copy(maxTokens = it)) }
                NumberField(
                    label = stringResource(R.string.ai_timeout_sec),
                    value = config.timeoutSec,
                    range = 5..600,
                ) { update(config.copy(timeoutSec = it)) }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(stringResource(R.string.ai_tools_title)) {
                SwitchRow(
                    label = stringResource(R.string.ai_tools_enable),
                    checked = config.toolsEnabled,
                    description = stringResource(R.string.ai_tools_enable_desc),
                ) { update(config.copy(toolsEnabled = it)) }
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    label = stringResource(R.string.ai_allow_shell),
                    checked = config.allowShell,
                    description = stringResource(R.string.ai_allow_shell_desc),
                ) { update(config.copy(allowShell = it)) }
                Spacer(Modifier.height(8.dp))
                NumberField(
                    label = stringResource(R.string.ai_max_steps),
                    value = config.maxSteps,
                    range = 1..50,
                ) { update(config.copy(maxSteps = it)) }
                Text(
                    stringResource(R.string.ai_max_steps_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(stringResource(R.string.ai_prompt_title)) {
                Text(
                    stringResource(R.string.ai_prompt_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = config.systemPrompt,
                    onValueChange = { update(config.copy(systemPrompt = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    update(config.copy(systemPrompt = AiPrompts.DEFAULT_AGENT_PROMPT))
                }) { Text(stringResource(R.string.ai_prompt_reset)) }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(stringResource(R.string.ai_test_title)) {
                Text(
                    stringResource(R.string.ai_test_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    enabled = !testing && config.isConfigured,
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            testResult = withContext(Dispatchers.IO) {
                                runCatching {
                                    AiClient(
                                        config.copy(stream = false, toolsEnabled = false, maxTokens = 16),
                                        container.logBus,
                                    ).chat(
                                        messages = listOf(ChatMessage(role = "user", content = "ping")),
                                        tools = emptyList(),
                                    ).content
                                }.fold(
                                    onSuccess = { context.getString(R.string.ai_test_success, it.take(120)) },
                                    onFailure = { context.getString(R.string.ai_test_failure, it.message.orEmpty()) },
                                )
                            }
                            testing = false
                        }
                    },
                ) { Text(stringResource(if (testing) R.string.ai_testing else R.string.ai_test)) }
                testResult?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
