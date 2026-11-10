package com.benton.izukijs.ui.permission

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.benton.izukijs.R
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
