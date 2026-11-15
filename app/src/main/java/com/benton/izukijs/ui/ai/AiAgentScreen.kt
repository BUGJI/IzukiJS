package com.benton.izukijs.ui.ai

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.ai.AiClient
import com.benton.izukijs.ai.AiConfig
import com.benton.izukijs.ai.AiPrompts
import com.benton.izukijs.ai.AiProviderPreset
import com.benton.izukijs.ai.AiProviders
import com.benton.izukijs.ai.ChatMessage
import com.benton.izukijs.model.Capability
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.ConfirmDialog
import com.benton.izukijs.ui.common.DecimalField
import com.benton.izukijs.ui.common.IzukiPageScaffold
import com.benton.izukijs.ui.common.LabeledField
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.NumberField
import com.benton.izukijs.ui.common.PageColumn
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SectionCardHeaderGap
import com.benton.izukijs.ui.common.SectionGap
import com.benton.izukijs.ui.common.SecretField
import com.benton.izukijs.ui.common.StatusRow
import com.benton.izukijs.ui.common.SwitchRow
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 连接测试的互斥状态；文案在组合阶段渲染，避免协程里持有已过期的本地化字符串。 */
private sealed interface TestState {
    data object Idle : TestState
    data object Testing : TestState
    data class Success(val content: String) : TestState
    data class Failure(val message: String) : TestState
}

@Composable
fun AiAgentScreen(
    onBack: () -> Unit,
    onOpenSetup: () -> Unit = {},
) {
    val container = rememberAppContainer()
    val scope = rememberCoroutineScope()
    val config by container.aiConfigRepository.config.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarController.current
    val readyModes by container.controllerManager.readyModes.collectAsStateWithLifecycle()
    val shellReady = remember(readyModes) {
        container.controllerManager.controllerFor(Capability.SHELL) != null
    }

    var testState by remember { mutableStateOf<TestState>(TestState.Idle) }
    var confirmResetPrompt by remember { mutableStateOf(false) }
    var confirmAllowShell by remember { mutableStateOf(false) }
    var pendingPreset by remember { mutableStateOf<AiProviderPreset?>(null) }

    fun update(new: AiConfig) = container.aiConfigRepository.save(new)

    // 连接参数变化后旧的测试结论不再可信，回到未测试状态。
    LaunchedEffect(
        config.baseUrl,
        config.apiKey,
        config.model,
        config.extraHeaderName,
        config.extraHeaderValue,
    ) {
        testState = TestState.Idle
    }

    // 系统提示词本地编辑态：非编辑（dirty=false）时才跟随外部值，兼容「重置 / 导入」。
    // 用 rememberSaveable 保留旋转前的未提交草稿。
    var promptText by rememberSaveable { mutableStateOf(config.systemPrompt) }
    var promptDirty by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(config.systemPrompt) {
        if (!promptDirty) promptText = config.systemPrompt
    }
    // 离开页面时兜底提交聚焦状态下未失焦的改动。
    // config 也必须取最新值，否则会把页面打开后修改的其它字段回滚成旧值。
    val latestPrompt by rememberUpdatedState(promptText)
    val latestPromptDirty by rememberUpdatedState(promptDirty)
    val latestConfig by rememberUpdatedState(config)
    DisposableEffect(Unit) {
        onDispose {
            if (latestPromptDirty) update(latestConfig.copy(systemPrompt = latestPrompt))
        }
    }

    val promptModified = promptText != AiPrompts.DEFAULT_AGENT_PROMPT
    val resetDoneText = stringResource(R.string.ai_prompt_reset_done)
    val headerIncomplete = config.extraHeaderName.isBlank() != config.extraHeaderValue.isBlank()
    val activePreset = AiProviders.match(config.baseUrl)

    fun applyPreset(preset: AiProviderPreset) {
        update(config.copy(baseUrl = preset.baseUrl, model = preset.model))
        testState = TestState.Idle
    }

    fun runTest() {
        testState = TestState.Testing
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AiClient(
                        config.copy(stream = false, toolsEnabled = false, maxTokens = 16),
                        container.logBus,
                    ).chat(
                        messages = listOf(ChatMessage(role = "user", content = "ping")),
                        tools = emptyList(),
                    ).content
                }
            }
            testState = result.fold(
                onSuccess = { TestState.Success(it.take(120)) },
                onFailure = { TestState.Failure(it.message.orEmpty()) },
            )
        }
    }

    IzukiPageScaffold(
        title = { Text(stringResource(R.string.ai_title)) },
        onBack = onBack,
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

            Spacer(Modifier.height(SectionGap))
            SectionCard(stringResource(R.string.ai_api_title)) {
                Text(
                    stringResource(R.string.ai_api_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(SectionCardHeaderGap))
                Text(
                    stringResource(R.string.ai_provider_title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                ChipFlow {
                    AiProviders.presets.forEach { preset ->
                        FilterChip(
                            selected = activePreset?.id == preset.id,
                            onClick = {
                                val isCustom = config.baseUrl.isNotBlank() && activePreset == null
                                if (isCustom) pendingPreset = preset else applyPreset(preset)
                            },
                            label = { Text(stringResource(preset.labelRes)) },
                        )
                    }
                }
                LabeledField(
                    label = stringResource(R.string.ai_base_url),
                    value = config.baseUrl,
                    placeholder = AiConfig.DEFAULT_BASE_URL,
                    commitOnBlur = true,
                ) {
                    update(config.copy(baseUrl = it))
                }
                SecretField(stringResource(R.string.ai_api_key), config.apiKey, commitOnBlur = true) {
                    update(config.copy(apiKey = it))
                }
                LabeledField(
                    label = stringResource(R.string.ai_model),
                    value = config.model,
                    placeholder = AiConfig.DEFAULT_MODEL,
                    commitOnBlur = true,
                ) {
                    update(config.copy(model = it))
                }
                LabeledField(
                    label = stringResource(R.string.ai_custom_header_name),
                    value = config.extraHeaderName,
                    placeholder = stringResource(R.string.ai_custom_header_name_placeholder),
                    commitOnBlur = true,
                ) { update(config.copy(extraHeaderName = it)) }
                SecretField(stringResource(R.string.ai_custom_header_value), config.extraHeaderValue, commitOnBlur = true) {
                    update(config.copy(extraHeaderValue = it))
                }
                if (headerIncomplete) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.ai_header_pair_incomplete),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(SectionGap))
            SectionCard(stringResource(R.string.ai_test_title)) {
                Text(
                    stringResource(R.string.ai_test_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(SectionCardHeaderGap))
                OutlinedButton(
                    enabled = testState !is TestState.Testing && config.canTest,
                    onClick = ::runTest,
                ) {
                    Text(
                        stringResource(
                            if (testState is TestState.Testing) R.string.ai_testing else R.string.ai_test,
                        ),
                    )
                }
                Spacer(Modifier.height(SectionCardHeaderGap))
                when (val state = testState) {
                    TestState.Idle -> if (!config.canTest) {
                        Text(
                            stringResource(R.string.ai_test_requires_config),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    TestState.Testing -> Text(
                        stringResource(R.string.ai_testing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    is TestState.Success -> Text(
                        stringResource(R.string.ai_test_success, state.content),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    is TestState.Failure -> Text(
                        stringResource(R.string.ai_test_failure, state.message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(SectionGap))
            SectionCard(stringResource(R.string.ai_params_title)) {
                SwitchRow(
                    label = stringResource(R.string.ai_stream),
                    checked = config.stream,
                    description = stringResource(R.string.ai_stream_desc),
                ) {
                    update(config.copy(stream = it))
                }
                Spacer(Modifier.height(SectionCardHeaderGap))
                DecimalField(
                    label = stringResource(R.string.ai_temperature),
                    value = config.temperature,
                    range = 0.0..2.0,
                    commitOnBlur = true,
                ) { update(config.copy(temperature = it)) }
                NumberField(
                    label = stringResource(R.string.ai_max_tokens),
                    value = config.maxTokens,
                    range = 1..32000,
                    commitOnBlur = true,
                ) { update(config.copy(maxTokens = it)) }
                NumberField(
                    label = stringResource(R.string.ai_timeout_sec),
                    value = config.timeoutSec,
                    range = 5..600,
                    commitOnBlur = true,
                ) { update(config.copy(timeoutSec = it)) }
            }

            Spacer(Modifier.height(SectionGap))
            SectionCard(stringResource(R.string.ai_tools_title)) {
                SwitchRow(
                    label = stringResource(R.string.ai_tools_enable),
                    checked = config.toolsEnabled,
                    description = stringResource(R.string.ai_tools_enable_desc),
                ) { update(config.copy(toolsEnabled = it)) }
                Spacer(Modifier.height(SectionCardHeaderGap))
                NumberField(
                    label = stringResource(R.string.ai_max_steps),
                    value = config.maxSteps,
                    range = 1..AiConfig.MAX_STEPS_LIMIT,
                    commitOnBlur = true,
                ) { update(config.copy(maxSteps = it)) }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.ai_max_steps_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(SectionCardHeaderGap))
                SwitchRow(
                    label = stringResource(R.string.ai_allow_shell),
                    checked = config.allowShell,
                    description = stringResource(
                        if (config.toolsEnabled) {
                            R.string.ai_allow_shell_desc
                        } else {
                            R.string.ai_allow_shell_needs_tools
                        },
                    ),
                    enabled = config.toolsEnabled || config.allowShell,
                ) { checked ->
                    if (checked) confirmAllowShell = true else update(config.copy(allowShell = false))
                }
                Spacer(Modifier.height(4.dp))
                StatusRow(
                    label = stringResource(R.string.ai_shell_backend),
                    value = stringResource(if (shellReady) R.string.common_ready else R.string.common_not_ready),
                    valueColor = if (shellReady) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if (!shellReady) {
                    TextButton(onClick = onOpenSetup) {
                        Text(stringResource(R.string.perm_action_authorize))
                    }
                }
            }

            Spacer(Modifier.height(SectionGap))
            SectionCard(
                title = stringResource(R.string.ai_prompt_title),
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (promptModified) {
                            Text(
                                stringResource(R.string.ai_prompt_modified),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        TextButton(
                            enabled = promptModified,
                            onClick = { confirmResetPrompt = true },
                        ) { Text(stringResource(R.string.ai_prompt_reset)) }
                    }
                },
            ) {
                // 本地暂存提示词，仅在失焦 / 离开页面时回写，避免长文本逐字符触发加密落盘与重组。
                OutlinedTextField(
                    value = promptText,
                    onValueChange = {
                        promptText = it
                        promptDirty = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { state ->
                            if (!state.isFocused && promptDirty) {
                                update(config.copy(systemPrompt = promptText))
                                promptDirty = false
                            }
                        },
                    minLines = 6,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmResetPrompt) {
        ConfirmDialog(
            title = stringResource(R.string.ai_prompt_reset),
            message = stringResource(R.string.ai_prompt_reset_confirm),
            onConfirm = {
                confirmResetPrompt = false
                promptDirty = false
                promptText = AiPrompts.DEFAULT_AGENT_PROMPT
                update(config.copy(systemPrompt = AiPrompts.DEFAULT_AGENT_PROMPT))
                scope.launch { snackbar.show(resetDoneText) }
            },
            onDismiss = { confirmResetPrompt = false },
        )
    }

    if (confirmAllowShell) {
        ConfirmDialog(
            title = stringResource(R.string.ai_allow_shell),
            message = stringResource(R.string.ai_allow_shell_confirm),
            destructive = true,
            onConfirm = {
                confirmAllowShell = false
                update(config.copy(allowShell = true))
            },
            onDismiss = { confirmAllowShell = false },
        )
    }

    pendingPreset?.let { preset ->
        ConfirmDialog(
            title = stringResource(R.string.ai_provider_override_title),
            message = stringResource(R.string.ai_provider_override_confirm, stringResource(preset.labelRes)),
            onConfirm = {
                pendingPreset = null
                applyPreset(preset)
            },
            onDismiss = { pendingPreset = null },
        )
    }
}
