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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.ai.AiClient
import com.benton.izukijs.ai.AiConfig
import com.benton.izukijs.ai.AiPrompts
import com.benton.izukijs.ai.ChatMessage
import com.benton.izukijs.ui.common.LabeledField
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
        PageColumn(
            modifier = Modifier.padding(padding).padding(16.dp),
        ) {
            SectionCard("总开关") {
                SwitchRow(
                    label = "启用 AI Agent",
                    checked = config.enabled,
                    description = "关闭后脚本的 ai.* 调用会直接返回 null。",
                ) {
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
                LabeledField(
                    label = "Base URL",
                    value = config.baseUrl,
                    placeholder = AiConfig.DEFAULT_BASE_URL,
                ) {
                    update(config.copy(baseUrl = it))
                }
                SecretField("API Key", config.apiKey) { update(config.copy(apiKey = it)) }
                LabeledField(
                    label = "模型",
                    value = config.model,
                    placeholder = AiConfig.DEFAULT_MODEL,
                ) {
                    update(config.copy(model = it))
                }
                LabeledField(
                    label = "自定义鉴权 Header 名",
                    value = config.extraHeaderName,
                    placeholder = "可选，如 api-key（Azure）",
                ) { update(config.copy(extraHeaderName = it)) }
                SecretField("自定义鉴权 Header 值", config.extraHeaderValue) {
                    update(config.copy(extraHeaderValue = it))
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard("生成参数") {
                SwitchRow(
                    label = "流式返回",
                    checked = config.stream,
                    description = "开启后通过 onDelta 回调逐段返回文本。",
                ) {
                    update(config.copy(stream = it))
                }
                Spacer(Modifier.height(8.dp))
                LabeledField(
                    label = "Temperature（0–2）",
                    value = config.temperature.toString(),
                    keyboardType = KeyboardType.Decimal,
                ) { text ->
                    text.toDoubleOrNull()?.let { update(config.copy(temperature = it.coerceIn(0.0, 2.0))) }
                }
                LabeledField(
                    label = "最大 Token",
                    value = config.maxTokens.toString(),
                    keyboardType = KeyboardType.Number,
                ) { text ->
                    text.toIntOrNull()?.let { update(config.copy(maxTokens = it.coerceIn(1, 32000))) }
                }
                LabeledField(
                    label = "超时（秒）",
                    value = config.timeoutSec.toString(),
                    keyboardType = KeyboardType.Number,
                ) { text ->
                    text.toIntOrNull()?.let { update(config.copy(timeoutSec = it.coerceIn(5, 600))) }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard("Agent 工具") {
                SwitchRow(
                    label = "启用工具调用",
                    checked = config.toolsEnabled,
                    description = "允许模型自己点击 / 滑动 / OCR / 启动应用等。关闭则仅做纯对话。",
                ) { update(config.copy(toolsEnabled = it)) }
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    label = "允许 Shell",
                    checked = config.allowShell,
                    description = "允许模型执行 Shell 命令，需要 Shizuku 或 Root，权限较大，默认关闭。",
                ) { update(config.copy(allowShell = it)) }
                Spacer(Modifier.height(8.dp))
                LabeledField(
                    label = "最大步数",
                    value = config.maxSteps.toString(),
                    keyboardType = KeyboardType.Number,
                ) { text ->
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
