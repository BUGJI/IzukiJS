package com.benton.izukijs.ui.permission

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.benton.izukijs.R
import com.benton.izukijs.ui.common.SwitchRow

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
