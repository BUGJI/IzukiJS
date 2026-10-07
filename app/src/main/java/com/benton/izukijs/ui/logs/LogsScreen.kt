package com.benton.izukijs.ui.logs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.ui.console.ConsolePanel
import com.benton.izukijs.ui.rememberAppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen() {
    val container = rememberAppContainer()
    val logs by container.logBus.entries.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("日志") }) },
    ) { padding ->
        ConsolePanel(
            entries = logs,
            onClear = { container.logBus.clear() },
            showHeader = false,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}
