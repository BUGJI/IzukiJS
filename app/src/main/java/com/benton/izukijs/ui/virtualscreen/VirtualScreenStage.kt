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
internal fun InteractiveStage(
    resolution: VirtualScreenResolution,
    interactive: Boolean,
    scriptRunning: Boolean,
    inputAvailable: Boolean,
    firstFrameArrived: Boolean,
    currentPackage: String?,
    launchingApp: Boolean,
    screenshotLoading: Boolean,
    ocrLoading: Boolean,
    maxPreviewHeight: Dp,
    onChooseApp: () -> Unit,
    onKeyboard: () -> Unit,
    onKey: (Int) -> Unit,
    onScreenshot: () -> Unit,
    onOcr: () -> Unit,
    onFullscreen: () -> Unit,
    onTap: (Float, Float) -> Unit,
    onLongPress: (Float, Float) -> Unit,
    onSwipe: (Float, Float, Float, Float, Long) -> Unit,
) {
    val dockLabel = stringResource(R.string.virtual_screen_quick_actions)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val layout = VirtualScreenDockLayout.compute(
            availableWidth = maxWidth.value,
            aspectRatio = resolution.aspectRatio,
            maxPreviewHeight = maxPreviewHeight.value,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                InteractivePreview(
                    resolution = resolution,
                    interactive = interactive,
                    scriptRunning = scriptRunning,
                    inputAvailable = inputAvailable,
                    owner = VirtualScreenManager.PAGE_PREVIEW_OWNER,
                    firstFrameArrived = firstFrameArrived,
                    showFullscreenButton = true,
                    maxPreviewHeight = layout.previewHeight.dp,
                    onFullscreen = onFullscreen,
                    onTap = onTap,
                    onLongPress = onLongPress,
                    onSwipe = onSwipe,
                )
            }
            if (layout.showDock) {
                Spacer(Modifier.width(VirtualScreenDockLayout.DOCK_GAP_DP.dp))
                PreviewActionDock(
                    columns = layout.dockColumns,
                    modifier = Modifier
                        .width(layout.dockWidth.dp)
                        .semantics { contentDescription = dockLabel },
                    interactive = interactive,
                    appEnabled = !scriptRunning && !launchingApp,
                    currentPackage = currentPackage,
                    launchingApp = launchingApp,
                    screenshotLoading = screenshotLoading,
                    ocrLoading = ocrLoading,
                    onChooseApp = onChooseApp,
                    onKeyboard = onKeyboard,
                    onKey = onKey,
                    onScreenshot = onScreenshot,
                    onOcr = onOcr,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewActionDock(
    columns: Int,
    interactive: Boolean,
    appEnabled: Boolean,
    currentPackage: String?,
    launchingApp: Boolean,
    screenshotLoading: Boolean,
    ocrLoading: Boolean,
    onChooseApp: () -> Unit,
    onKeyboard: () -> Unit,
    onKey: (Int) -> Unit,
    onScreenshot: () -> Unit,
    onOcr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        maxItemsInEachRow = columns,
        horizontalArrangement = Arrangement.spacedBy(VirtualScreenDockLayout.BUTTON_GAP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(VirtualScreenDockLayout.BUTTON_GAP_DP.dp),
    ) {
        DockButton(
            contentDescription = stringResource(R.string.virtual_screen_apps_choose),
            enabled = appEnabled,
            onClick = onChooseApp,
        ) {
            when {
                launchingApp -> {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                currentPackage != null -> {
                    AppIcon(packageName = currentPackage, modifier = Modifier.size(28.dp))
                }
                else -> Icon(Icons.Filled.Apps, contentDescription = null)
            }
        }
        DockButton(
            contentDescription = stringResource(R.string.virtual_screen_key_back),
            enabled = interactive,
            onClick = { onKey(KeyEvent.KEYCODE_BACK) },
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
        }
        DockButton(
            contentDescription = stringResource(R.string.virtual_screen_keyboard),
            enabled = interactive,
            onClick = onKeyboard,
        ) {
            Icon(Icons.Filled.Keyboard, contentDescription = null)
        }
        DockButton(
            contentDescription = stringResource(
                if (screenshotLoading) R.string.virtual_screen_screenshot_busy
                else R.string.virtual_screen_screenshot,
            ),
            enabled = !screenshotLoading,
            onClick = onScreenshot,
        ) {
            if (screenshotLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null)
            }
        }
        DockButton(
            contentDescription = stringResource(
                if (ocrLoading) R.string.virtual_screen_ocr_busy
                else R.string.virtual_screen_ocr,
            ),
            enabled = !ocrLoading,
            onClick = onOcr,
        ) {
            if (ocrLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.TextFields, contentDescription = null)
            }
        }
    }
}

@Composable
private fun DockButton(
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(VirtualScreenDockLayout.BUTTON_SIZE_DP.dp)
            .semantics { this.contentDescription = contentDescription },
    ) {
        content()
    }
}


@Composable
internal fun FullscreenPreviewDialog(
    resolution: VirtualScreenResolution,
    interactive: Boolean,
    scriptRunning: Boolean,
    inputAvailable: Boolean,
    firstFrameArrived: Boolean,
    onDismiss: () -> Unit,
    onTap: (Float, Float) -> Unit,
    onLongPress: (Float, Float) -> Unit,
    onSwipe: (Float, Float, Float, Float, Long) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding(),
        ) {
            InteractivePreview(
                resolution = resolution,
                interactive = interactive,
                scriptRunning = scriptRunning,
                inputAvailable = inputAvailable,
                owner = VirtualScreenManager.FULLSCREEN_PREVIEW_OWNER,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                maxPreviewHeight = null,
                firstFrameArrived = firstFrameArrived,
                showFullscreenButton = false,
                onFullscreen = null,
                onTap = onTap,
                onLongPress = onLongPress,
                onSwipe = onSwipe,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.virtual_screen_exit_fullscreen),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun InteractivePreview(
    resolution: VirtualScreenResolution,
    interactive: Boolean,
    scriptRunning: Boolean,
    inputAvailable: Boolean,
    owner: String,
    modifier: Modifier = Modifier,
    maxPreviewHeight: Dp? = VirtualScreenDockLayout.PHONE_MAX_PREVIEW_HEIGHT_DP.dp,
    firstFrameArrived: Boolean = true,
    showFullscreenButton: Boolean = false,
    onFullscreen: (() -> Unit)? = null,
    onTap: (Float, Float) -> Unit,
    onLongPress: (Float, Float) -> Unit,
    onSwipe: (Float, Float, Float, Float, Long) -> Unit,
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val ratio = resolution.aspectRatio.coerceIn(
        VirtualScreenDockLayout.MIN_PREVIEW_RATIO,
        VirtualScreenDockLayout.MAX_PREVIEW_RATIO,
    )
    val viewport = remember(viewSize, resolution) {
        VirtualScreenTouchMapper.viewport(
            viewWidth = viewSize.width.toFloat(),
            viewHeight = viewSize.height.toFloat(),
            screenWidth = resolution.width,
            screenHeight = resolution.height,
        )
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val previewHeight = minOf(maxWidth / ratio, maxPreviewHeight ?: this.maxHeight)
        val previewWidth = previewHeight * ratio
        Box(
            modifier = Modifier
                .width(previewWidth)
                .height(previewHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .onSizeChanged { viewSize = it },
        ) {
            VirtualScreenSurface(
                owner = owner,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .virtualScreenGestures(
                        viewport = viewport,
                        resolution = resolution,
                        enabled = interactive,
                        onTap = onTap,
                        onLongPress = onLongPress,
                        onSwipe = onSwipe,
                    ),
            )
            if (!interactive) {
                PreviewOverlay(
                    text = when {
                        scriptRunning -> stringResource(R.string.virtual_screen_preview_readonly_script)
                        !inputAvailable -> stringResource(R.string.virtual_screen_preview_readonly_input)
                        else -> stringResource(R.string.virtual_screen_preview_readonly)
                    },
                )
            } else if (!firstFrameArrived) {
                PreviewOverlay(
                    text = stringResource(R.string.virtual_screen_preview_waiting_first_frame),
                )
            }
            if (showFullscreenButton && onFullscreen != null) {
                IconButton(
                    onClick = onFullscreen,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                ) {
                    Icon(
                        Icons.Filled.Fullscreen,
                        contentDescription = stringResource(R.string.virtual_screen_fullscreen),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewOverlay(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(12.dp),
        )
    }
}

private fun Modifier.virtualScreenGestures(
    viewport: PreviewViewport,
    resolution: VirtualScreenResolution,
    enabled: Boolean,
    onTap: (Float, Float) -> Unit,
    onLongPress: (Float, Float) -> Unit,
    onSwipe: (Float, Float, Float, Float, Long) -> Unit,
): Modifier = pointerInput(viewport, resolution, enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        if (!VirtualScreenTouchMapper.inside(start.x, start.y, viewport)) {
            return@awaitEachGesture
        }
        down.consume()
        val startTime = SystemClock.elapsedRealtime()
        val startPoint = VirtualScreenTouchMapper.map(
            start.x,
            start.y,
            viewport,
            resolution.width,
            resolution.height,
        ) ?: return@awaitEachGesture
        var dragging = false
        var longPressSent = false

        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - startTime
            val timeout = viewConfiguration.longPressTimeoutMillis - elapsed
            val event = if (!dragging && !longPressSent && timeout > 0L) {
                withTimeoutOrNull(timeout) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }

            if (event == null) {
                longPressSent = true
                onLongPress(startPoint.x, startPoint.y)
                continue
            }

            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) {
                if (dragging) {
                    val end = VirtualScreenTouchMapper.map(
                        change.position.x,
                        change.position.y,
                        viewport,
                        resolution.width,
                        resolution.height,
                    )
                    if (end != null) {
                        onSwipe(
                            startPoint.x,
                            startPoint.y,
                            end.x,
                            end.y,
                            (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(1L),
                        )
                    }
                } else if (!longPressSent) {
                    val point = VirtualScreenTouchMapper.map(
                        change.position.x,
                        change.position.y,
                        viewport,
                        resolution.width,
                        resolution.height,
                    )
                    if (point != null) onTap(point.x, point.y)
                }
                break
            }

            if (!longPressSent && !dragging &&
                (change.position - start).getDistance() > viewConfiguration.touchSlop
            ) {
                dragging = true
            }
        }
    }
}


