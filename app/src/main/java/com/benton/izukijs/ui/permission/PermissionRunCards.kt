package com.benton.izukijs.ui.permission

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.benton.izukijs.R
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.LabeledField
import com.benton.izukijs.ui.common.SwitchRow

@Composable
internal fun HttpCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.perm_http_title),
        description = stringResource(R.string.perm_http_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_medium), Rating.WARN),
            Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
        ),
    )
    PermissionCard(
        info = info,
        status = stringResource(
            if (state.httpSettings.enabled) R.string.perm_status_on else R.string.perm_status_off,
        ),
        ready = state.httpSettings.enabled,
        onShowInfo = { actions.showInfo(info) },
        action = {
            Column {
                SwitchRow(
                    label = stringResource(R.string.perm_http_enable),
                    description = stringResource(R.string.perm_http_desc),
                    checked = state.httpSettings.enabled,
                ) { actions.saveHttpSettings(state.httpSettings.copy(enabled = it)) }
                if (state.httpSettings.enabled) {
                    LabeledField(
                        label = stringResource(R.string.perm_http_allowlist),
                        value = state.httpSettings.allowlist,
                        placeholder = stringResource(R.string.perm_http_allowlist_hint),
                    ) { actions.saveHttpSettings(state.httpSettings.copy(allowlist = it)) }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.perm_http_allowlist_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
internal fun WakeCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.perm_wake_title),
        description = stringResource(R.string.perm_wake_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_low), Rating.GOOD),
        ),
    )
    PermissionCard(
        info = info,
        status = stringResource(
            if (state.runtimeSettings.keepAwake) R.string.perm_status_on else R.string.perm_status_off,
        ),
        ready = state.runtimeSettings.keepAwake,
        onShowInfo = { actions.showInfo(info) },
        action = {
            Column {
                SwitchRow(
                    label = stringResource(R.string.perm_wake_title),
                    checked = state.runtimeSettings.keepAwake,
                    description = stringResource(R.string.perm_wake_desc),
                ) { actions.saveRuntimeSettings(state.runtimeSettings.copy(keepAwake = it)) }
                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    label = stringResource(R.string.perm_wake_screen),
                    checked = state.runtimeSettings.wakeScreen,
                    description = stringResource(R.string.perm_wake_screen_desc),
                    enabled = state.runtimeSettings.keepAwake,
                ) { actions.saveRuntimeSettings(state.runtimeSettings.copy(wakeScreen = it)) }
            }
        },
    )
}

@Composable
internal fun UnlockCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.perm_unlock_title),
        description = stringResource(R.string.perm_unlock_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_medium), Rating.WARN),
        ),
    )
    val configured = state.unlockSettings.hasCredential
    PermissionCard(
        info = info,
        status = stringResource(
            when {
                !state.unlockSettings.enabled -> R.string.perm_status_off
                configured -> R.string.perm_status_on
                else -> R.string.perm_unlock_status_incomplete
            },
        ),
        ready = state.unlockSettings.enabled && configured,
        onShowInfo = { actions.showInfo(info) },
        action = {
            OutlinedButton(onClick = actions.openUnlockSettings) {
                Text(stringResource(R.string.perm_unlock_open_settings))
            }
        },
    )
}

@Composable
internal fun TimeoutCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.perm_timeout_title),
        description = stringResource(R.string.perm_timeout_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_low), Rating.GOOD),
        ),
    )
    val status = if (state.runtimeSettings.scriptTimeoutMinutes <= 0) {
        stringResource(R.string.perm_timeout_unlimited)
    } else {
        stringResource(R.string.perm_timeout_minutes, state.runtimeSettings.scriptTimeoutMinutes)
    }
    PermissionCard(
        info = info,
        status = status,
        ready = true,
        onShowInfo = { actions.showInfo(info) },
        action = {
            Column {
                ChipFlow {
                    listOf(0, 10, 30, 60, 120).forEach { minutes ->
                        FilterChip(
                            selected = state.runtimeSettings.scriptTimeoutMinutes == minutes,
                            onClick = {
                                actions.saveRuntimeSettings(
                                    state.runtimeSettings.copy(scriptTimeoutMinutes = minutes),
                                )
                            },
                            label = {
                                Text(
                                    if (minutes == 0) {
                                        stringResource(R.string.perm_timeout_unlimited)
                                    } else {
                                        stringResource(R.string.perm_timeout_minutes, minutes)
                                    },
                                )
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
internal fun KeepAliveCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.perm_keepalive_title),
        description = stringResource(R.string.perm_keepalive_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_low), Rating.GOOD),
            Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
        ),
    )
    PermissionCard(
        info = info,
        status = stringResource(
            if (state.ignoringBatteryOptimizations) R.string.perm_status_on else R.string.perm_status_off,
        ),
        ready = state.ignoringBatteryOptimizations,
        onShowInfo = { actions.showInfo(info) },
        action = {
            Column {
                Text(
                    stringResource(
                        if (state.ignoringBatteryOptimizations) {
                            R.string.perm_battery_ok
                        } else {
                            R.string.perm_battery_warn
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.ignoringBatteryOptimizations) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = actions.openBatterySettings) {
                    Text(stringResource(R.string.perm_battery_request))
                }
            }
        },
    )
}

@Composable
internal fun TileCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.perm_tile_title),
        description = stringResource(R.string.perm_tile_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
        ),
    )
    val tileReady = state.runtimeSettings.tileEnabled && state.pinnedScriptName != null
    PermissionCard(
        info = info,
        status = stringResource(
            when {
                !state.runtimeSettings.tileEnabled -> R.string.perm_status_off
                state.pinnedScriptName == null -> R.string.perm_tile_enabled_unbound
                else -> R.string.perm_status_on
            },
        ),
        ready = tileReady,
        onShowInfo = { actions.showInfo(info) },
        action = {
            Column {
                SwitchRow(
                    label = stringResource(R.string.perm_tile_enable),
                    checked = state.runtimeSettings.tileEnabled,
                ) { actions.saveRuntimeSettings(state.runtimeSettings.copy(tileEnabled = it)) }
                Spacer(Modifier.height(8.dp))
                Text(
                    state.pinnedScriptName?.let { stringResource(R.string.perm_tile_pinned, it) }
                        ?: stringResource(R.string.perm_tile_pick_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    state.runtimeSettings.tileEnabled &&
                    state.pinnedScriptName != null
                ) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = actions.addTile) {
                        Text(stringResource(R.string.perm_tile_add))
                    }
                } else {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.perm_tile_manual_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}
