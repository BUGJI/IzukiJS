package com.benton.izukijs.ui.mcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.mcp.McpBindMode
import com.benton.izukijs.mcp.McpConfig
import com.benton.izukijs.mcp.McpHttpHandler
import com.benton.izukijs.service.McpServerService
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.LabeledField
import com.benton.izukijs.ui.common.NumberField
import com.benton.izukijs.ui.common.PageColumn
import com.benton.izukijs.ui.common.SecretField
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SwitchRow
import com.benton.izukijs.ui.common.stableTopAppBarColors
import com.benton.izukijs.ui.rememberAppContainer
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServerScreen(onBack: () -> Unit) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val config by container.mcpConfigRepository.config.collectAsStateWithLifecycle()
    val status by container.mcpServerController.status.collectAsStateWithLifecycle()

    var message by remember { mutableStateOf<String?>(null) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    fun current() = container.mcpConfigRepository.current()

    /** 保存配置；若服务正在运行则按需重建监听。 */
    fun apply(new: McpConfig, restart: Boolean = true) {
        container.mcpConfigRepository.save(new)
        if (restart && current().enabled) McpServerService.restart(context)
    }

    fun copy(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        message = context.getString(R.string.mcp_copied)
    }

    val endpoint = status.addresses.firstOrNull().orEmpty()
    val isRunning = status.running

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mcp_title)) },
                scrollBehavior = scrollBehavior,
                colors = stableTopAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        PageColumn(modifier = Modifier.padding(padding).padding(16.dp)) {

            Text(
                stringResource(R.string.mcp_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            SectionCard(stringResource(R.string.mcp_master_title)) {
                SwitchRow(
                    label = stringResource(R.string.mcp_enable),
                    checked = config.enabled,
                    description = stringResource(R.string.mcp_enable_desc),
                ) { enabled ->
                    if (enabled) {
                        val token = current().token.ifBlank { McpConfig.newToken() }
                        container.mcpConfigRepository.save(current().copy(enabled = true, token = token))
                        McpServerService.start(context)
                    } else {
                        container.mcpConfigRepository.save(current().copy(enabled = false))
                        McpServerService.stop(context)
                    }
                }
                Spacer(Modifier.height(8.dp))
                val statusText = when {
                    status.error != null -> stringResource(R.string.mcp_status_error, status.error.orEmpty())
                    isRunning -> stringResource(R.string.mcp_status_running)
                    else -> stringResource(R.string.mcp_status_stopped)
                }
                Text(statusText, style = MaterialTheme.typography.bodyMedium)
                if (isRunning) {
                    Text(
                        stringResource(R.string.mcp_sessions, status.sessions),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.mcp_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(stringResource(R.string.mcp_endpoint_title)) {
                Text(
                    stringResource(R.string.mcp_endpoint_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    endpoint.ifBlank {
                        if (config.bindMode == McpBindMode.LOCALHOST) {
                            "http://127.0.0.1:${config.port}${McpHttpHandler.ENDPOINT}"
                        } else {
                            stringResource(R.string.mcp_status_stopped)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = endpoint.isNotBlank(),
                        onClick = { copy("endpoint", endpoint) },
                    ) { Text(stringResource(R.string.mcp_copy_endpoint)) }
                    OutlinedButton(onClick = { copy("config", buildClientConfig(endpoint, config.token)) }) {
                        Text(stringResource(R.string.mcp_copy_config))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(stringResource(R.string.mcp_token_title)) {
                Text(
                    stringResource(R.string.mcp_token_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                SecretField(
                    label = stringResource(R.string.mcp_token_label),
                    value = config.token,
                ) { apply(config.copy(token = it)) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    apply(config.copy(token = McpConfig.newToken()))
                    message = context.getString(R.string.mcp_token_regenerated)
                }) { Text(stringResource(R.string.mcp_token_regenerate)) }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(stringResource(R.string.mcp_network_title)) {
                Text(stringResource(R.string.mcp_bind_mode), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                ChipFlow {
                    FilterChip(
                        selected = config.bindMode == McpBindMode.LAN,
                        onClick = { apply(config.copy(bindMode = McpBindMode.LAN)) },
                        label = { Text(stringResource(R.string.mcp_bind_lan)) },
                    )
                    FilterChip(
                        selected = config.bindMode == McpBindMode.LOCALHOST,
                        onClick = { apply(config.copy(bindMode = McpBindMode.LOCALHOST)) },
                        label = { Text(stringResource(R.string.mcp_bind_localhost)) },
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        if (config.bindMode == McpBindMode.LAN) {
                            R.string.mcp_bind_lan_desc
                        } else {
                            R.string.mcp_bind_localhost_desc
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (config.bindMode == McpBindMode.LOCALHOST) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.mcp_localhost_hint, config.port),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NumberField(
                    label = stringResource(R.string.mcp_port),
                    value = config.port,
                    range = McpConfig.MIN_PORT..McpConfig.MAX_PORT,
                ) { apply(config.copy(port = it)) }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(stringResource(R.string.mcp_tools_title)) {
                Text(
                    stringResource(R.string.mcp_tools_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    label = stringResource(R.string.mcp_allow_shell),
                    checked = config.allowShell,
                    description = stringResource(R.string.mcp_allow_shell_desc),
                ) { apply(config.copy(allowShell = it)) }
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    label = stringResource(R.string.mcp_allow_scripts),
                    checked = config.allowScripts,
                    description = stringResource(R.string.mcp_allow_scripts_desc),
                ) { apply(config.copy(allowScripts = it)) }
            }

            message?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 生成可直接粘贴到 MCP 客户端的配置 JSON。 */
private fun buildClientConfig(endpoint: String, token: String): String {
    val url = endpoint.ifBlank { "http://127.0.0.1:8765/mcp" }
    return JSONObject().apply {
        put(
            "mcpServers",
            JSONObject().put(
                "izuki",
                JSONObject().apply {
                    put("url", url)
                    put("headers", JSONObject().put("Authorization", "Bearer $token"))
                },
            ),
        )
    }.toString(2)
}
