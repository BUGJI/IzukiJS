package com.benton.izukijs.ui.virtualscreen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.util.LruCache
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.ui.appViewModel
import com.benton.izukijs.ui.common.ActionButtons
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.ConfirmDialog
import com.benton.izukijs.ui.common.IzukiPageScaffold
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.PageColumn
import com.benton.izukijs.ui.common.PageMaxWidth
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SectionGap
import com.benton.izukijs.ui.common.StatusRow
import com.benton.izukijs.ui.common.formatDateTime
import com.benton.izukijs.ui.common.shareUri
import com.benton.izukijs.ui.rememberAppContainer
import com.benton.izukijs.ui.theme.LocalIzukiExtraColors
import com.benton.izukijs.virtualscreen.DeviceScreenResolution
import com.benton.izukijs.virtualscreen.ScreenTarget
import com.benton.izukijs.virtualscreen.VirtualScreenManager
import com.benton.izukijs.virtualscreen.VirtualScreenResolution
import com.benton.izukijs.virtualscreen.VirtualScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
@Composable
internal fun DisplayCard(
    resolution: VirtualScreenResolution,
    presets: List<VirtualScreenResolution>,
    deviceResolution: VirtualScreenResolution,
    busy: Boolean,
    scriptRunning: Boolean,
    onSelect: (VirtualScreenResolution) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val canChange = !scriptRunning && !busy
    SectionCard(
        title = stringResource(R.string.virtual_screen_display_title),
        trailing = {
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.virtual_screen_collapse
                        else R.string.virtual_screen_expand,
                    ),
                )
            }
        },
    ) {
        Text(
            stringResource(
                R.string.virtual_screen_current_resolution,
                resolution.width,
                resolution.height,
                resolution.dpi,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            ChipFlow {
                presets.forEach { preset ->
                    FilterChip(
                        selected = resolution == preset,
                        onClick = { onSelect(preset) },
                        enabled = canChange,
                        label = {
                            Text(
                                if (preset == deviceResolution) {
                                    stringResource(
                                        R.string.virtual_screen_device_preset,
                                        preset.width,
                                        preset.height,
                                    )
                                } else {
                                    "${preset.width}×${preset.height}"
                                },
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            ActionButtons {
                OutlinedButton(
                    onClick = { onSelect(resolution.swapped()) },
                    enabled = canChange,
                ) {
                    Icon(Icons.Filled.ScreenRotation, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.virtual_screen_rotate))
                }
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.virtual_screen_more_resolution))
                }
            }
            if (scriptRunning) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.virtual_screen_resolution_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun DiagnosticsCard(
    state: VirtualScreenState,
    active: VirtualScreenState.Active?,
    resolution: VirtualScreenResolution,
    frameSeq: Long,
    target: ScreenTarget,
    resolvedTarget: ScreenTarget,
    pipAttached: Boolean,
    lastStopReason: String?,
    lastStopAt: Long,
    onCopy: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val none = stringResource(R.string.virtual_screen_diag_value_none)
    val displayValue = active?.displayId?.toString() ?: none
    val frameValue = if (active != null) frameSeq.toString() else none
    val targetValue = if (target == ScreenTarget.AUTO) {
        stringResource(
            R.string.virtual_screen_target_auto_resolved,
            targetText(resolvedTarget),
        )
    } else {
        targetText(target)
    }
    SectionCard(
        title = stringResource(R.string.virtual_screen_diagnostics_title),
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCopy) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.virtual_screen_diag_copy),
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(
                            if (expanded) R.string.virtual_screen_collapse
                            else R.string.virtual_screen_expand,
                        ),
                    )
                }
            }
        },
    ) {
        Text(
            stringResource(
                R.string.virtual_screen_diag_summary,
                stateLabel(state),
                displayValue,
                frameValue,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusRow(
                    stringResource(R.string.virtual_screen_diag_state),
                    stateLabel(state),
                )
                StatusRow(
                    stringResource(R.string.virtual_screen_diag_display),
                    displayValue,
                )
                StatusRow(
                    stringResource(R.string.virtual_screen_diag_resolution),
                    "${resolution.width}×${resolution.height}@${resolution.dpi}",
                )
                StatusRow(
                    stringResource(R.string.virtual_screen_diag_frame),
                    frameValue,
                )
                StatusRow(stringResource(R.string.virtual_screen_diag_target), targetValue)
                StatusRow(
                    stringResource(R.string.virtual_screen_diag_pip),
                    stringResource(
                        if (pipAttached) {
                            R.string.virtual_screen_diag_pip_attached
                        } else {
                            R.string.virtual_screen_diag_pip_none
                        },
                    ),
                )
                if (lastStopReason != null && lastStopAt > 0L) {
                    StatusRow(
                        stringResource(R.string.virtual_screen_diag_last_stop),
                        stringResource(
                            R.string.virtual_screen_diag_last_stop_value,
                            stopReasonLabel(lastStopReason),
                            formatDateTime(lastStopAt),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun stateLabel(state: VirtualScreenState): String = stringResource(
    when (state) {
        VirtualScreenState.Idle -> R.string.virtual_screen_status_short_idle
        VirtualScreenState.Starting -> R.string.virtual_screen_status_short_starting
        is VirtualScreenState.Active -> R.string.virtual_screen_status_short_active
        is VirtualScreenState.Failed -> R.string.virtual_screen_status_short_failed
    },
)

@Composable
private fun targetText(target: ScreenTarget): String = stringResource(
    when (target) {
        ScreenTarget.AUTO -> R.string.virtual_screen_target_auto
        ScreenTarget.VIRTUAL -> R.string.virtual_screen_target_virtual
        ScreenTarget.MAIN -> R.string.virtual_screen_target_main
    },
)

@Composable
private fun stopReasonLabel(reason: String): String = stringResource(
    when (reason) {
        VirtualScreenManager.STOP_USER -> R.string.virtual_screen_stop_reason_user_stop
        VirtualScreenManager.STOP_SERVICE_DISCONNECTED ->
            R.string.virtual_screen_stop_reason_service_disconnected
        VirtualScreenManager.STOP_APP_RESTART -> R.string.virtual_screen_stop_reason_app_restart
        VirtualScreenManager.STOP_RESOLUTION_CHANGED ->
            R.string.virtual_screen_stop_reason_resolution_changed
        VirtualScreenManager.STOP_RUN_FINISHED -> R.string.virtual_screen_stop_reason_run_finished
        else -> R.string.virtual_screen_stop_reason_unknown
    },
)

internal fun diagnosticText(
    state: VirtualScreenState,
    active: VirtualScreenState.Active?,
    resolution: VirtualScreenResolution,
    frameSeq: Long,
    target: ScreenTarget,
    resolvedTarget: ScreenTarget,
    pipAttached: Boolean,
    lastStopReason: String?,
    lastStopAt: Long,
): String = buildString {
    appendLine("state=${stateKey(state)}")
    appendLine("displayId=${active?.displayId ?: -1}")
    appendLine("resolution=${resolution.width}x${resolution.height}@${resolution.dpi}")
    appendLine("frameSeq=$frameSeq")
    appendLine("target=${target.name.lowercase()}")
    appendLine("resolvedTarget=${resolvedTarget.name.lowercase()}")
    appendLine("pipPreview=$pipAttached")
    lastStopReason?.let { appendLine("lastStopReason=$it") }
    if (lastStopAt > 0L) appendLine("lastStopAt=$lastStopAt")
    if (state is VirtualScreenState.Failed) appendLine("message=${state.message}")
}

private fun stateKey(state: VirtualScreenState): String = when (state) {
    VirtualScreenState.Idle -> "idle"
    VirtualScreenState.Starting -> "starting"
    is VirtualScreenState.Active -> "active"
    is VirtualScreenState.Failed -> "failed"
}

