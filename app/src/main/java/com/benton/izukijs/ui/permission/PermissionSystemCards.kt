package com.benton.izukijs.ui.permission

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.benton.izukijs.R
import com.benton.izukijs.ui.common.SwitchRow

@Composable
internal fun FloatingCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.perm_floating_title),
        description = stringResource(R.string.perm_floating_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_low), Rating.GOOD),
            Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
        ),
        tip = stringResource(R.string.perm_floating_tip),
    )
    PermissionCard(
        info = info,
        status = stringResource(
            if (state.floatingEnabled || state.debugOverlayEnabled) R.string.perm_status_on else R.string.perm_status_off,
        ),
        ready = state.floatingEnabled || state.debugOverlayEnabled,
        onShowInfo = { actions.showInfo(info) },
        action = {
            Column {
                SwitchRow(
                    label = stringResource(R.string.perm_floating_control),
                    checked = state.floatingEnabled,
                ) { checked -> actions.setFloating(checked) }
                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    label = stringResource(R.string.perm_floating_debug),
                    checked = state.debugOverlayEnabled,
                    description = stringResource(R.string.perm_floating_debug_desc),
                ) { checked -> actions.setDebugOverlay(checked) }
                if (!state.canDrawOverlays) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.perm_overlay_needed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
internal fun UsageAccessCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.perm_usage_title),
        description = stringResource(R.string.perm_usage_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_accuracy), stringResource(R.string.rating_high), Rating.GOOD),
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_low), Rating.GOOD),
            Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
        ),
    )
    PermissionCard(
        info = info,
        status = stringResource(
            if (state.usageAccessGranted) R.string.perm_status_authorized else R.string.perm_status_unauthorized,
        ),
        ready = state.usageAccessGranted,
        onShowInfo = { actions.showInfo(info) },
        action = {
            SystemPermissionAction(granted = state.usageAccessGranted) {
                actions.openUsageAccessSettings()
            }
        },
    )
}

@Composable
internal fun NotificationListenerCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.perm_notify_title),
        description = stringResource(R.string.perm_notify_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_accuracy), stringResource(R.string.rating_high), Rating.GOOD),
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_low), Rating.GOOD),
            Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
        ),
    )
    PermissionCard(
        info = info,
        status = stringResource(
            if (state.notificationListenerGranted) R.string.perm_status_authorized else R.string.perm_status_unauthorized,
        ),
        ready = state.notificationListenerGranted,
        onShowInfo = { actions.showInfo(info) },
        action = {
            SystemPermissionAction(granted = state.notificationListenerGranted) {
                actions.openNotificationListenerSettings()
            }
        },
    )
}

@Composable
internal fun AppListCard(state: PermissionUiState, actions: PermissionActions) {
    val enabled = state.runtimeSettings.appList
    val info = PermissionInfo(
        title = stringResource(R.string.perm_applist_title),
        description = stringResource(R.string.perm_applist_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_low), Rating.GOOD),
            Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
        ),
    )
    PermissionCard(
        info = info,
        status = stringResource(if (enabled) R.string.perm_status_on else R.string.perm_status_off),
        ready = enabled,
        onShowInfo = { actions.showInfo(info) },
        action = {
            SwitchRow(
                label = stringResource(R.string.perm_applist_switch),
                checked = enabled,
            ) { actions.saveRuntimeSettings(state.runtimeSettings.copy(appList = it)) }
        },
    )
}

@Composable
internal fun AllFilesCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.perm_allfiles_title),
        description = stringResource(R.string.perm_allfiles_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_medium), Rating.WARN),
            Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
        ),
    )
    PermissionCard(
        info = info,
        status = stringResource(
            if (state.allFilesGranted) R.string.perm_status_authorized else R.string.perm_status_unauthorized,
        ),
        ready = state.allFilesGranted,
        onShowInfo = { actions.showInfo(info) },
        action = {
            SystemPermissionAction(granted = state.allFilesGranted) {
                actions.requestAllFiles()
            }
        },
    )
}
