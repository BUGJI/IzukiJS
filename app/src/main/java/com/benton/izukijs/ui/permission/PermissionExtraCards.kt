package com.benton.izukijs.ui.permission

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.benton.izukijs.R

@Composable
internal fun NotificationsCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.perm_notifications_title),
        description = stringResource(R.string.perm_notifications_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_low), Rating.GOOD),
            Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
        ),
    )
    PermissionCard(
        info = info,
        status = stringResource(
            if (state.notificationsGranted) R.string.perm_status_authorized else R.string.perm_status_unauthorized,
        ),
        ready = state.notificationsGranted,
        onShowInfo = { actions.showInfo(info) },
        action = {
            SystemPermissionAction(granted = state.notificationsGranted) {
                if (state.notificationsGranted) {
                    actions.openNotificationSettings()
                } else {
                    actions.requestNotifications()
                }
            }
        },
    )
}

@Composable
internal fun OverlayCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.perm_overlay_title),
        description = stringResource(R.string.perm_overlay_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_medium), Rating.WARN),
            Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
        ),
    )
    PermissionCard(
        info = info,
        status = stringResource(
            if (state.overlayGranted) R.string.perm_status_authorized else R.string.perm_status_unauthorized,
        ),
        ready = state.overlayGranted,
        onShowInfo = { actions.showInfo(info) },
        action = {
            SystemPermissionAction(granted = state.overlayGranted) { actions.requestOverlay() }
        },
    )
}

@Composable
internal fun BluetoothCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.perm_bluetooth_title),
        description = stringResource(R.string.perm_bluetooth_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_low), Rating.GOOD),
            Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
        ),
    )
    PermissionCard(
        info = info,
        status = stringResource(
            if (state.bluetoothGranted) R.string.perm_status_authorized else R.string.perm_status_unauthorized,
        ),
        ready = state.bluetoothGranted,
        onShowInfo = { actions.showInfo(info) },
        action = {
            SystemPermissionAction(granted = state.bluetoothGranted) {
                if (state.bluetoothGranted) actions.openAppSettings() else actions.requestBluetooth()
            }
        },
    )
}

@Composable
internal fun LocalNetworkCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.perm_local_network_title),
        description = stringResource(R.string.perm_local_network_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_low), Rating.GOOD),
            Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
        ),
    )
    PermissionCard(
        info = info,
        status = stringResource(
            if (state.localNetworkGranted) R.string.perm_status_authorized else R.string.perm_status_unauthorized,
        ),
        ready = state.localNetworkGranted,
        onShowInfo = { actions.showInfo(info) },
        action = {
            SystemPermissionAction(granted = state.localNetworkGranted) {
                if (state.localNetworkGranted) actions.openAppSettings() else actions.requestLocalNetwork()
            }
        },
    )
}

@Composable
internal fun ExactAlarmCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.perm_exact_alarm_title),
        description = stringResource(R.string.perm_exact_alarm_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_low), Rating.GOOD),
            Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
        ),
    )
    PermissionCard(
        info = info,
        status = stringResource(
            if (state.exactAlarmGranted) R.string.perm_status_authorized else R.string.perm_status_unauthorized,
        ),
        ready = state.exactAlarmGranted,
        onShowInfo = { actions.showInfo(info) },
        action = {
            SystemPermissionAction(granted = state.exactAlarmGranted) { actions.requestExactAlarm() }
        },
    )
}

@Composable
internal fun InstallUnknownAppsCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.perm_install_apps_title),
        description = stringResource(R.string.perm_install_apps_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_medium), Rating.WARN),
            Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
        ),
    )
    PermissionCard(
        info = info,
        status = stringResource(
            if (state.installUnknownAppsGranted) {
                R.string.perm_status_authorized
            } else {
                R.string.perm_status_unauthorized
            },
        ),
        ready = state.installUnknownAppsGranted,
        onShowInfo = { actions.showInfo(info) },
        action = {
            SystemPermissionAction(granted = state.installUnknownAppsGranted) {
                actions.requestInstallUnknownApps()
            }
        },
    )
}
