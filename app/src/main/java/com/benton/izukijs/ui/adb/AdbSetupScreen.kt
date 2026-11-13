package com.benton.izukijs.ui.adb

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.controller.adb.AdbConnectionManager
import com.benton.izukijs.core.permission.LocalNetworkPermission
import com.benton.izukijs.service.AdbPairingService
import com.benton.izukijs.ui.common.IzukiPageScaffold
import com.benton.izukijs.ui.common.PageMaxWidth
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.launch

private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

private enum class AdbPendingAction {
    START_PAIRING,
    DISCOVER,
    MDNS_CONNECT,
    GRANT_LOCAL_NETWORK,
}

/**
 * 无线调试设置页：配对、连接、断开与忘记身份。
 *
 * 配对需要用户在系统「无线调试 → 使用配对码配对设备」中保持对话框打开；
 * 配对端口优先通过 mDNS 自动发现，失败时可手动填写。
 * Android 17 起还需本地网络权限，否则系统会用 NsdPickerActivity 拦截 mDNS。
 */
@Composable
fun AdbSetupScreen(onBack: () -> Unit) {
    val container = rememberAppContainer()
    val manager = container.adbManager
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    val state by manager.state.collectAsStateWithLifecycle()
    val endpoint by manager.pairingDiscovery.endpoint.collectAsStateWithLifecycle()

    var host by remember { mutableStateOf(manager.preferredHost().orEmpty()) }
    var pairPort by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var manualBusy by remember { mutableStateOf(false) }
    var showForgetDialog by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<AdbPendingAction?>(null) }

    var permissionRefresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionRefresh++
                manager.refreshState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val localNetworkRequired = manager.localNetworkPermissionRequired()
    val localNetworkGranted = remember(permissionRefresh) { manager.hasLocalNetworkPermission() }
    val notificationGranted = remember(permissionRefresh) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                POST_NOTIFICATIONS_PERMISSION,
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                POST_NOTIFICATIONS_PERMISSION,
            ) == PackageManager.PERMISSION_GRANTED

    fun missingPermissions(action: AdbPendingAction): Array<String> = buildList {
        if (action == AdbPendingAction.START_PAIRING && !hasNotificationPermission()) {
            add(POST_NOTIFICATIONS_PERMISSION)
        }
        if (localNetworkRequired && !LocalNetworkPermission.isGranted(context)) {
            add(LocalNetworkPermission.permission)
        }
    }.toTypedArray()

    fun openDevOptions() {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
    }

    fun launchNotificationPairing() {
        AdbPairingService.start(context)
        message = resources.getString(R.string.adb_notification_pairing_hint)
        openDevOptions()
    }

    fun perform(action: AdbPendingAction) {
        when (action) {
            AdbPendingAction.START_PAIRING -> launchNotificationPairing()
            AdbPendingAction.DISCOVER -> {
                manager.pairingDiscovery.start()
                message = null
            }
            AdbPendingAction.MDNS_CONNECT -> scope.launch {
                if (!manager.hasIdentity()) {
                    actionMessage = resources.getString(R.string.adb_message_no_identity)
                    return@launch
                }
                manualBusy = true
                try {
                    val ok = manager.connect()
                    actionMessage = resources.getString(
                        if (ok) R.string.adb_message_connected else R.string.adb_message_connect_failed,
                    )
                } finally {
                    manualBusy = false
                }
            }
            AdbPendingAction.GRANT_LOCAL_NETWORK -> manager.refreshState()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        permissionRefresh++
        if (!LocalNetworkPermission.isGranted(context)) {
            message = resources.getString(R.string.adb_message_local_network_denied)
            pendingAction = null
            return@rememberLauncherForActivityResult
        }
        val action = pendingAction
        pendingAction = null
        if (action == AdbPendingAction.START_PAIRING && !hasNotificationPermission()) {
            message = resources.getString(R.string.adb_notify_permission_needed)
            return@rememberLauncherForActivityResult
        }
        action?.let { perform(it) }
    }

    fun requirePermissionsFor(action: AdbPendingAction) {
        val missing = missingPermissions(action)
        if (missing.isEmpty()) {
            perform(action)
        } else {
            pendingAction = action
            permissionLauncher.launch(missing)
        }
    }

    val supported = manager.isSupported()
    val connected = state is AdbConnectionManager.State.Connected
    val busy = manualBusy ||
        state is AdbConnectionManager.State.Pairing ||
        state is AdbConnectionManager.State.Connecting

    LaunchedEffect(Unit) {
        manager.refreshState()
    }
    LaunchedEffect(endpoint) {
        endpoint?.let {
            host = it.host
            pairPort = it.port.toString()
        }
    }
    LaunchedEffect(state) {
        if (state is AdbConnectionManager.State.Connected) {
            manager.pairingDiscovery.stop()
        }
    }
    DisposableEffect(Unit) {
        onDispose { manager.pairingDiscovery.stop() }
    }

    val status = when {
        !supported -> stringResource(R.string.adb_state_unsupported)
        localNetworkRequired && !localNetworkGranted ->
            stringResource(R.string.adb_state_local_network)
        state is AdbConnectionManager.State.NeedsLocalNetworkPermission ->
            stringResource(R.string.adb_state_local_network)
        state is AdbConnectionManager.State.Connected -> stringResource(R.string.adb_state_connected)
        state is AdbConnectionManager.State.Pairing -> stringResource(R.string.adb_state_pairing)
        state is AdbConnectionManager.State.Connecting -> stringResource(R.string.adb_state_connecting)
        state is AdbConnectionManager.State.Error ->
            stringResource(R.string.adb_state_error) + ": " +
                (state as AdbConnectionManager.State.Error).message
        manager.hasIdentity() -> stringResource(R.string.adb_state_disconnected)
        else -> stringResource(R.string.adb_state_needs_pairing)
    }

    fun resolveHost(): String? =
        host.ifBlank { manager.preferredHost().orEmpty() }.ifBlank {
            actionMessage = resources.getString(R.string.adb_message_no_host)
            null
        }

    IzukiPageScaffold(
        title = { Text(stringResource(R.string.control_adb)) },
        onBack = onBack,
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = PageMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(R.string.control_adb), style = MaterialTheme.typography.titleMedium)
                        Text(status, color = if (connected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        })
                        if (!supported) {
                            Text(
                                stringResource(R.string.adb_state_unsupported),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        message?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.adb_setup_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = { openDevOptions() },
                            enabled = supported,
                        ) {
                            Text(stringResource(R.string.adb_open_dev_options))
                        }
                    }
                }

                if (supported) {
                    if (localNetworkRequired && !localNetworkGranted) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    stringResource(R.string.adb_local_network_title),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    stringResource(R.string.adb_local_network_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(onClick = {
                                    pendingAction = AdbPendingAction.GRANT_LOCAL_NETWORK
                                    permissionLauncher.launch(arrayOf(LocalNetworkPermission.permission))
                                }) {
                                    Text(stringResource(R.string.adb_grant_local_network))
                                }
                                TextButton(onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                "package:${context.packageName}".toUri(),
                                            ),
                                        )
                                    }
                                }) {
                                    Text(stringResource(R.string.adb_open_app_settings))
                                }
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(R.string.adb_notification_pairing_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.adb_notification_pairing_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!notificationGranted) {
                                Text(
                                    stringResource(R.string.adb_notify_permission_needed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Button(
                                onClick = { requirePermissionsFor(AdbPendingAction.START_PAIRING) },
                                enabled = supported && (localNetworkGranted || !localNetworkRequired),
                            ) {
                                Text(stringResource(R.string.adb_start_notification_pairing))
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                stringResource(R.string.adb_step_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(stringResource(R.string.adb_step_1), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.adb_step_2), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.adb_step_3), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.adb_step_4), style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(R.string.adb_connection_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { requirePermissionsFor(AdbPendingAction.MDNS_CONNECT) },
                                    enabled = (localNetworkGranted || !localNetworkRequired) &&
                                        manager.hasIdentity() && !busy,
                                ) { Text(stringResource(R.string.adb_mdns_connect)) }
                                OutlinedButton(
                                    enabled = connected && !busy,
                                    onClick = {
                                        scope.launch {
                                            manager.disconnect()
                                            actionMessage = resources.getString(R.string.adb_message_disconnected)
                                        }
                                    },
                                ) { Text(stringResource(R.string.adb_disconnect)) }
                            }
                            OutlinedButton(
                                onClick = { showForgetDialog = true },
                                enabled = manager.hasIdentity() && !busy,
                            ) { Text(stringResource(R.string.adb_forget)) }
                            actionMessage?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.adb_advanced_title),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                                    Text(
                                        stringResource(
                                            if (advancedExpanded) {
                                                R.string.adb_advanced_collapse
                                            } else {
                                                R.string.adb_advanced_expand
                                            },
                                        ),
                                    )
                                }
                            }
                            Text(
                                stringResource(R.string.adb_advanced_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (advancedExpanded) {
                                Text(
                                    endpoint?.let {
                                        stringResource(R.string.adb_discovered_endpoint, it.host, it.port)
                                    } ?: stringResource(R.string.adb_no_endpoint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { requirePermissionsFor(AdbPendingAction.DISCOVER) },
                                        enabled = localNetworkGranted || !localNetworkRequired,
                                    ) { Text(stringResource(R.string.adb_start_discovery)) }
                                    OutlinedButton(onClick = {
                                        manager.pairingDiscovery.stop()
                                    }) { Text(stringResource(R.string.adb_stop_discovery)) }
                                }
                                OutlinedTextField(
                                    value = host,
                                    onValueChange = { host = it },
                                    label = { Text(stringResource(R.string.adb_host_label)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    value = pairPort,
                                    onValueChange = { pairPort = it.filter(Char::isDigit) },
                                    label = { Text(stringResource(R.string.adb_pair_port_label)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    value = pairCode,
                                    onValueChange = { pairCode = it.filter(Char::isDigit).take(6) },
                                    label = { Text(stringResource(R.string.adb_pair_code_label)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                                Button(
                                    enabled = pairCode.length == 6 &&
                                        (localNetworkGranted || !localNetworkRequired) && !busy,
                                    onClick = {
                                        scope.launch {
                                            val resolvedHost = resolveHost() ?: return@launch
                                            val port = pairPort.toIntOrNull() ?: endpoint?.port
                                            if (port == null) {
                                                actionMessage =
                                                    resources.getString(R.string.adb_message_need_pair_port)
                                                return@launch
                                            }
                                            if (pairCode.length != 6) {
                                                actionMessage =
                                                    resources.getString(R.string.adb_message_need_pair_code)
                                                return@launch
                                            }
                                            manualBusy = true
                                            actionMessage = resources.getString(R.string.adb_message_pairing)
                                            try {
                                                val ok = manager.pairAndConnect(resolvedHost, port, pairCode)
                                                actionMessage = resources.getString(
                                                    if (ok) R.string.adb_message_pair_success
                                                    else R.string.adb_message_pair_failed,
                                                )
                                            } finally {
                                                manualBusy = false
                                            }
                                        }
                                    },
                                ) { Text(stringResource(R.string.adb_pair_connect)) }

                                Spacer(Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = connectPort,
                                    onValueChange = { connectPort = it.filter(Char::isDigit) },
                                    label = { Text(stringResource(R.string.adb_connect_port_label)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                                OutlinedButton(
                                    enabled = (localNetworkGranted || !localNetworkRequired) && !busy,
                                    onClick = {
                                        scope.launch {
                                            val resolvedHost = resolveHost() ?: return@launch
                                            val port = connectPort.toIntOrNull()
                                            if (port == null) {
                                                actionMessage =
                                                    resources.getString(R.string.adb_message_need_connect_port)
                                                return@launch
                                            }
                                            manualBusy = true
                                            try {
                                                val ok = manager.connectManual(resolvedHost, port)
                                                actionMessage = resources.getString(
                                                    if (ok) R.string.adb_message_connected
                                                    else R.string.adb_message_connect_failed,
                                                )
                                            } finally {
                                                manualBusy = false
                                            }
                                        }
                                    },
                                ) { Text(stringResource(R.string.adb_manual_connect)) }

                                if (busy) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Text(
                                            stringResource(R.string.adb_action_working),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                actionMessage?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(R.string.adb_note_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(stringResource(R.string.adb_note_reboot), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.adb_note_network), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.adb_note_scope), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (showForgetDialog) {
        AlertDialog(
            onDismissRequest = { showForgetDialog = false },
            title = { Text(stringResource(R.string.adb_forget_title)) },
            text = { Text(stringResource(R.string.adb_forget_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showForgetDialog = false
                    scope.launch {
                        manager.forget()
                        pairCode = ""
                        pairPort = ""
                        message = resources.getString(R.string.adb_message_forgot)
                    }
                }) { Text(stringResource(R.string.adb_forget_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showForgetDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
