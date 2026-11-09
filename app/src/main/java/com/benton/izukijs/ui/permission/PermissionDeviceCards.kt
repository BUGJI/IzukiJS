package com.benton.izukijs.ui.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.benton.izukijs.R
import com.benton.izukijs.controller.adb.AdbConnectionManager

@Composable
internal fun ScreenCaptureCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.perm_screen_capture),
        description = stringResource(R.string.perm_capture_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_accuracy), stringResource(R.string.rating_high), Rating.GOOD),
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_medium), Rating.WARN),
            Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
        ),
        pros = listOf(
            stringResource(R.string.perm_capture_pro_1),
            stringResource(R.string.perm_capture_pro_2),
        ),
        cons = listOf(
            stringResource(R.string.perm_capture_con_1),
            stringResource(R.string.perm_capture_con_2),
        ),
        tip = stringResource(R.string.perm_capture_tip),
    )
    PermissionCard(
        info = info,
        status = stringResource(
            if (state.screenCaptureActive) R.string.perm_status_recording else R.string.perm_status_off,
        ),
        ready = state.screenCaptureActive,
        onShowInfo = { actions.showInfo(info) },
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = actions.startScreenCapture,
                    enabled = !state.screenCaptureActive,
                ) {
                    Text(
                        stringResource(
                            if (state.screenCaptureActive) R.string.perm_status_recording else R.string.perm_action_authorize_start,
                        ),
                    )
                }
                if (state.screenCaptureActive) {
                    OutlinedButton(onClick = actions.stopScreenCapture) {
                        Text(stringResource(R.string.perm_action_stop_recording))
                    }
                }
            }
        },
    )
}

@Composable
internal fun HidCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.control_hid),
        description = stringResource(R.string.perm_hid_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_accuracy), stringResource(R.string.rating_high), Rating.GOOD),
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_very_low), Rating.GOOD),
            Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_medium), Rating.WARN),
        ),
        pros = listOf(
            stringResource(R.string.perm_hid_pro_1),
            stringResource(R.string.perm_hid_pro_2),
        ),
        cons = listOf(
            stringResource(R.string.perm_hid_con_1),
        ),
    )
    PermissionCard(
        info = info,
        status = stringResource(if (state.hidReady) R.string.common_ready else R.string.common_not_ready),
        ready = state.hidReady,
        onShowInfo = { actions.showInfo(info) },
        action = {
            CapabilityActions(
                actionLabel = stringResource(if (state.hidReady) R.string.perm_action_manage else R.string.perm_action_connect),
                onAction = actions.openHid,
                onRevoke = if (state.hidReady) {
                    ({ actions.revokeHid() })
                } else {
                    null
                },
            )
        },
    )
}

@Composable
internal fun AccessibilityCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.control_accessibility),
        description = stringResource(R.string.perm_accessibility_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_accuracy), stringResource(R.string.rating_high), Rating.GOOD),
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_high), Rating.BAD),
            Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_easy), Rating.GOOD),
        ),
        pros = listOf(
            stringResource(R.string.perm_accessibility_pro_1),
            stringResource(R.string.perm_accessibility_pro_2),
        ),
        cons = listOf(
            stringResource(R.string.perm_accessibility_con_1),
            stringResource(R.string.perm_accessibility_con_2),
        ),
    )
    PermissionCard(
        info = info,
        status = stringResource(if (state.accessibilityReady) R.string.common_ready else R.string.common_not_ready),
        ready = state.accessibilityReady,
        onShowInfo = { actions.showInfo(info) },
        action = {
            CapabilityActions(
                actionLabel = stringResource(
                    if (state.accessibilityReady) R.string.perm_status_on else R.string.perm_action_open,
                ),
                onAction = if (state.accessibilityReady) null else actions.openAccessibilitySettings,
                onRevoke = if (state.accessibilityReady) {
                    ({ actions.disableAccessibility() })
                } else {
                    null
                },
            )
        },
    )
}

@Composable
internal fun ShizukuCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.control_shizuku),
        description = stringResource(R.string.perm_shizuku_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_accuracy), stringResource(R.string.rating_high), Rating.GOOD),
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_medium), Rating.WARN),
            Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_complex), Rating.BAD),
        ),
        pros = listOf(
            stringResource(R.string.perm_shizuku_pro_1),
            stringResource(R.string.perm_shizuku_pro_2),
        ),
        cons = listOf(
            stringResource(R.string.perm_shizuku_con_1),
            stringResource(R.string.perm_shizuku_con_2),
        ),
    )
    PermissionCard(
        info = info,
        status = stringResource(
            when {
                state.shizukuReady -> R.string.common_ready
                !state.shizukuRunning -> R.string.perm_status_service_stopped
                else -> R.string.common_not_ready
            },
        ),
        ready = state.shizukuReady,
        onShowInfo = { actions.showInfo(info) },
        action = {
            CapabilityActions(
                actionLabel = stringResource(
                    when {
                        state.shizukuReady -> R.string.perm_status_authorized
                        !state.shizukuRunning -> R.string.perm_action_shizuku_not_running
                        else -> R.string.perm_action_authorize
                    },
                ),
                onAction = if (state.shizukuReady || !state.shizukuRunning) {
                    null
                } else {
                    { actions.requestShizuku() }
                },
                onRevoke = if (state.shizukuReady) ({ actions.revokeShizuku() }) else null,
            )
        },
    )
}

@Composable
internal fun AdbCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.control_adb),
        description = stringResource(R.string.perm_adb_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_accuracy), stringResource(R.string.rating_high), Rating.GOOD),
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_medium), Rating.WARN),
            Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_complex), Rating.BAD),
        ),
        pros = listOf(
            stringResource(R.string.perm_adb_pro_1),
            stringResource(R.string.perm_adb_pro_2),
        ),
        cons = listOf(
            stringResource(R.string.perm_adb_con_1),
            stringResource(R.string.perm_adb_con_2),
        ),
    )
    val status = when {
        !state.adbSupported -> R.string.adb_state_unsupported
        state.adbState is AdbConnectionManager.State.NeedsLocalNetworkPermission ->
            R.string.adb_state_local_network
        !state.adbHasLocalNetworkPermission -> R.string.adb_state_local_network
        state.adbReady -> R.string.common_ready
        state.adbState is AdbConnectionManager.State.Pairing -> R.string.adb_state_pairing
        state.adbState is AdbConnectionManager.State.Connecting -> R.string.adb_state_connecting
        state.adbState is AdbConnectionManager.State.Error -> R.string.adb_state_error
        state.adbHasIdentity -> R.string.adb_state_disconnected
        else -> R.string.adb_state_needs_pairing
    }
    PermissionCard(
        info = info,
        status = stringResource(status),
        ready = state.adbReady,
        onShowInfo = { actions.showInfo(info) },
        action = {
            CapabilityActions(
                actionLabel = stringResource(
                    if (state.adbReady) R.string.perm_action_manage_settings else R.string.perm_action_adb_setup,
                ),
                onAction = actions.openAdb,
                onRevoke = null,
            )
        },
    )
}

@Composable
internal fun RootCard(state: PermissionUiState, actions: PermissionActions) {
    val info = PermissionInfo(
        title = stringResource(R.string.control_root),
        description = stringResource(R.string.perm_root_desc),
        dimensions = listOf(
            Dimension(stringResource(R.string.dim_accuracy), stringResource(R.string.rating_high), Rating.GOOD),
            Dimension(stringResource(R.string.dim_risk), stringResource(R.string.rating_medium), Rating.WARN),
            Dimension(stringResource(R.string.dim_auth), stringResource(R.string.rating_complex), Rating.BAD),
        ),
        pros = listOf(
            stringResource(R.string.perm_root_pro_1),
            stringResource(R.string.perm_root_pro_2),
        ),
        cons = listOf(
            stringResource(R.string.perm_root_con_1),
        ),
    )
    PermissionCard(
        info = info,
        status = stringResource(if (state.rootReady) R.string.common_ready else R.string.common_not_ready),
        ready = state.rootReady,
        onShowInfo = { actions.showInfo(info) },
        action = {
            CapabilityActions(
                actionLabel = stringResource(
                    if (state.rootReady) R.string.common_ready else R.string.perm_action_detect_root,
                ),
                onAction = if (state.rootReady) null else ({ actions.refreshRoot() }),
                onRevoke = if (state.rootReady) ({ actions.revokeRoot() }) else null,
            )
        },
    )
}
