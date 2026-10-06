package com.benton.izukijs.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.ai.AiClient
import com.benton.izukijs.ai.AiConfig
import com.benton.izukijs.ai.AiPrompts
import com.benton.izukijs.ai.ChatMessage
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAgentScreen(onBack: () -> Unit) {
    val container = rememberAppContainer()
    val scope = rememberCoroutineScope()
    val config by container.aiConfigRepository.config.collectAsStateWithLifecycle()

    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    fun update(new: AiConfig) = container.aiConfigRepository.save(new)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Agent") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SectionCard("总开关") {
                SwitchRow("启用 AI Agent", "关闭后脚本的 ai.* 调用会直接返回 null。", config.enabled) {
                    update(config.copy(enabled = it))
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard("接口（OpenAI 兼容）") {
                Text(
                    "填写任意兼容 OpenAI Chat Completions 的服务地址；换 Base URL 即可对接 DeepSeek / 通义 / one-api / Ollama 等。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Field("Base URL", config.baseUrl, placeholder = AiConfig.DEFAULT_BASE_URL) {
                    update(config.copy(baseUrl = it))
                }
                SecretField("API Key", config.apiKey) { update(config.copy(apiKey = it)) }
                Field("模型", config.model, placeholder = AiConfig.DEFAULT_MODEL) {
                    update(config.copy(model = it))
                }
                Field(
                    "自定义鉴权 Header 名",
                    config.extraHeaderName,
                    placeholder = "可选，如 api-key（Azure）",
                ) { update(config.copy(extraHeaderName = it)) }
                SecretField("自定义鉴权 Header 值", config.extraHeaderValue) {
                    update(config.copy(extraHeaderValue = it))
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard("生成参数") {
                SwitchRow("流式返回", "开启后通过 onDelta 回调逐段返回文本。", config.stream) {
                    update(config.copy(stream = it))
                }
                Spacer(Modifier.height(8.dp))
                NumberField("Temperature（0–2）", config.temperature.toString()) { text ->
                    text.toDoubleOrNull()?.let { update(config.copy(temperature = it.coerceIn(0.0, 2.0))) }
                }
                NumberField("最大 Token", config.maxTokens.toString()) { text ->
                    text.toIntOrNull()?.let { update(config.copy(maxTokens = it.coerceIn(1, 32000))) }
                }
                NumberField("超时（秒）", config.timeoutSec.toString()) { text ->
                    text.toIntOrNull()?.let { update(config.copy(timeoutSec = it.coerceIn(5, 600))) }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard("Agent 工具") {
                SwitchRow(
                    "启用工具调用",
                    "允许模型自己点击 / 滑动 / OCR / 启动应用等。关闭则仅做纯对话。",
                    config.toolsEnabled,
                ) { update(config.copy(toolsEnabled = it)) }
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    "允许 Shell",
                    "允许模型执行 Shell 命令，需要 Shizuku 或 Root，权限较大，默认关闭。",
                    config.allowShell,
                ) { update(config.copy(allowShell = it)) }
                Spacer(Modifier.height(8.dp))
                NumberField("最大步数", config.maxSteps.toString()) { text ->
                    text.toIntOrNull()?.let { update(config.copy(maxSteps = it.coerceIn(1, 50))) }
                }
                Text(
                    "单个脚本可在调用时用 options.maxSteps 覆盖该值。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionCard("系统提示词") {
                Text(
                    "作为框架基础提示词发送给模型，描述所处环境、可用工具与决策规范。",
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
                }) { Text("恢复默认提示词") }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard("连接测试") {
                Text(
                    "发送一条极短的对话请求，验证地址 / Key / 模型是否可用。",
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
                                    onSuccess = { "✔ 成功：${it.take(120)}" },
                                    onFailure = { "✘ 失败：${it.message}" },
                                )
                            }
                            testing = false
                        }
                    },
                ) { Text(if (testing) "测试中…" else "测试连接") }
                testResult?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String = "",
    onChange: (String) -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) { Text(if (visible) "隐藏" else "显示") }
        },
    )
}
