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
internal fun PreviewCard(
    state: VirtualScreenState,
    resolution: VirtualScreenResolution,
    currentAppLabel: String?,
    currentPackage: String?,
    launchingApp: Boolean,
    scriptRunning: Boolean,
    runningScript: String?,
    inputAvailable: Boolean,
    interactive: Boolean,
    firstFrameArrived: Boolean,
    shizukuAvailable: Boolean,
    backgroundAllowed: Boolean,
    busy: Boolean,
    wide: Boolean,
    screenshotLoading: Boolean,
    ocrLoading: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStopScript: () -> Unit,
    onChooseApp: () -> Unit,
    onKeyboard: () -> Unit,
    onKey: (Int) -> Unit,
    onScreenshot: () -> Unit,
    onOcr: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenSettings: () -> Unit,
    onFullscreen: () -> Unit,
    onTap: (Float, Float) -> Unit,
    onLongPress: (Float, Float) -> Unit,
    onSwipe: (Float, Float, Float, Float, Long) -> Unit,
) {
    val active = state as? VirtualScreenState.Active
    val placeholderHeight = if (wide) WIDE_PLACEHOLDER_HEIGHT else PHONE_PLACEHOLDER_HEIGHT
    SectionCard(
        title = stringResource(R.string.virtual_screen_preview_title),
        trailing = { StatusPill(state) },
    ) {
        if (state is VirtualScreenState.Active) {
            Text(
                text = stringResource(
                    R.string.virtual_screen_current_app,
                    currentAppLabel ?: stringResource(R.string.virtual_screen_current_app_none),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
        }

        when (state) {
            VirtualScreenState.Idle -> IdlePreview(
                minHeight = placeholderHeight,
                shizukuAvailable = shizukuAvailable,
                backgroundAllowed = backgroundAllowed,
                busy = busy,
                scriptRunning = scriptRunning,
                onStart = onStart,
                onOpenSetup = onOpenSetup,
                onOpenSettings = onOpenSettings,
            )

            VirtualScreenState.Starting -> StartingPreview(minHeight = placeholderHeight)

            is VirtualScreenState.Active -> InteractiveStage(
                resolution = resolution,
                interactive = interactive,
                scriptRunning = scriptRunning,
                inputAvailable = inputAvailable,
                firstFrameArrived = firstFrameArrived,
                currentPackage = currentPackage,
                launchingApp = launchingApp,
                screenshotLoading = screenshotLoading,
                ocrLoading = ocrLoading,
                maxPreviewHeight = if (wide) {
                    VirtualScreenDockLayout.WIDE_MAX_PREVIEW_HEIGHT_DP.dp
                } else {
                    VirtualScreenDockLayout.PHONE_MAX_PREVIEW_HEIGHT_DP.dp
                },
                onChooseApp = onChooseApp,
                onKeyboard = onKeyboard,
                onKey = onKey,
                onScreenshot = onScreenshot,
                onOcr = onOcr,
                onFullscreen = onFullscreen,
                onTap = onTap,
                onLongPress = onLongPress,
                onSwipe = onSwipe,
            )

            is VirtualScreenState.Failed -> FailedPreview(
                message = state.message,
                minHeight = placeholderHeight,
                shizukuAvailable = shizukuAvailable,
                backgroundAllowed = backgroundAllowed,
                busy = busy,
                scriptRunning = scriptRunning,
                onStart = onStart,
                onOpenSetup = onOpenSetup,
                onOpenSettings = onOpenSettings,
            )
        }

        if (active != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        scriptRunning -> stringResource(
                            R.string.virtual_screen_script_running,
                            runningScript ?: stringResource(R.string.virtual_screen_script_unknown),
                        )
                        !inputAvailable -> stringResource(R.string.virtual_screen_input_unavailable)
                        else -> stringResource(R.string.virtual_screen_touch_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = if (scriptRunning) onStopScript else onStop,
                    enabled = !busy,
                ) {
                    Text(
                        stringResource(
                            if (scriptRunning) R.string.virtual_screen_stop_script
                            else R.string.virtual_screen_stop,
                        ),
                    )
                }
            }
        }
    }
}

/** 预览画面 + 右侧快捷操作栏；窄屏或短预览时自动把 dock 折成两列。 */
@Composable
private fun StatusPill(state: VirtualScreenState) {
    val extra = LocalIzukiExtraColors.current
    val color = when (state) {
        VirtualScreenState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
        VirtualScreenState.Starting -> MaterialTheme.colorScheme.primary
        is VirtualScreenState.Active -> extra.success
        is VirtualScreenState.Failed -> MaterialTheme.colorScheme.error
    }
    val label = stringResource(
        when (state) {
            VirtualScreenState.Idle -> R.string.virtual_screen_status_short_idle
            VirtualScreenState.Starting -> R.string.virtual_screen_status_short_starting
            is VirtualScreenState.Active -> R.string.virtual_screen_status_short_active
            is VirtualScreenState.Failed -> R.string.virtual_screen_status_short_failed
        },
    )
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun IdlePreview(
    minHeight: Dp,
    shizukuAvailable: Boolean,
    backgroundAllowed: Boolean,
    busy: Boolean,
    scriptRunning: Boolean,
    onStart: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.virtual_screen_preview_idle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                !shizukuAvailable -> {
                    Text(
                        stringResource(R.string.virtual_screen_shizuku_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = onOpenSetup) {
                        Text(stringResource(R.string.virtual_screen_open_setup))
                    }
                }

                !backgroundAllowed -> {
                    Text(
                        stringResource(R.string.virtual_screen_need_background),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = onOpenSettings) {
                        Text(stringResource(R.string.virtual_screen_open_settings))
                    }
                }

                else -> {
                    Button(onClick = onStart, enabled = !busy && !scriptRunning) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.virtual_screen_start))
                    }
                    Text(
                        stringResource(
                            if (scriptRunning) {
                                R.string.virtual_screen_script_running_touch_hint
                            } else {
                                R.string.virtual_screen_idle_start_hint
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun StartingPreview(minHeight: Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.virtual_screen_status_starting),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun FailedPreview(
    message: String,
    minHeight: Dp,
    shizukuAvailable: Boolean,
    backgroundAllowed: Boolean,
    busy: Boolean,
    scriptRunning: Boolean,
    onStart: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center,
            )
            when {
                !shizukuAvailable -> TextButton(onClick = onOpenSetup) {
                    Text(stringResource(R.string.virtual_screen_open_setup))
                }

                !backgroundAllowed -> TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.virtual_screen_open_settings))
                }

                else -> Button(onClick = onStart, enabled = !busy && !scriptRunning) {
                    Text(stringResource(R.string.virtual_screen_start))
                }
            }
            if (scriptRunning) {
                Text(
                    stringResource(R.string.virtual_screen_script_running_touch_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private val PHONE_PLACEHOLDER_HEIGHT = 180.dp
private val WIDE_PLACEHOLDER_HEIGHT = 220.dp
