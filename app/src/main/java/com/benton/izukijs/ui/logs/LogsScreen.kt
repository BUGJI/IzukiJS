package com.benton.izukijs.ui.logs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.ui.common.IzukiPageScaffold
import com.benton.izukijs.ui.common.newCacheExportFile
import com.benton.izukijs.ui.common.shareFile
import com.benton.izukijs.ui.console.ConsolePanel
import com.benton.izukijs.ui.rememberAppContainer
import com.benton.izukijs.virtualscreen.VirtualScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onOpenVirtualScreen: () -> Unit = {},
    isActivePage: Boolean = true,
) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    // 主界面四个标签常驻组合；非当前页时不订阅高频日志，避免脚本输出时后台页反复重组 / 布局。
    var logs by remember { mutableStateOf(container.logBus.entries.value) }
    LaunchedEffect(isActivePage) {
        if (!isActivePage) return@LaunchedEffect
        container.logBus.entries.collect { logs = it }
    }
    val virtualScreenState by container.virtualScreenManager.state.collectAsStateWithLifecycle()

    IzukiPageScaffold(
        title = { Text(stringResource(R.string.status_title)) },
        actions = {
            IconButton(
                onClick = {
                    scope.launch {
                        val file = newCacheExportFile(context, "izukijs_log", "log")
                        val ok = withContext(Dispatchers.IO) { container.logFileStore.exportTo(file) }
                        if (ok) {
                            shareFile(
                                context = context,
                                file = file,
                                chooserTitle = resources.getString(R.string.logs_export),
                            )
                        }
                    }
                },
            ) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.logs_export))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            VirtualScreenStatusCard(
                state = virtualScreenState,
                onClick = onOpenVirtualScreen,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            ConsolePanel(
                entries = logs,
                onClear = { container.logBus.clear() },
                showHeader = false,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun VirtualScreenStatusCard(
    state: VirtualScreenState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, modifier = modifier) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.virtual_screen_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (val current = state) {
                        VirtualScreenState.Idle -> stringResource(R.string.virtual_screen_status_idle)
                        VirtualScreenState.Starting -> stringResource(R.string.virtual_screen_status_starting)
                        is VirtualScreenState.Active -> stringResource(
                            R.string.virtual_screen_status_active,
                            current.displayId,
                            current.resolution.width,
                            current.resolution.height,
                            current.resolution.dpi,
                        )
                        is VirtualScreenState.Failed -> stringResource(
                            R.string.virtual_screen_status_failed,
                            current.message,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is VirtualScreenState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.width(12.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.virtual_screen_page_title),
            )
        }
    }
}
