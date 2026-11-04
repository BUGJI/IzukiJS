package com.benton.izukijs.ui.virtualscreen

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.ui.common.ConfirmDialog
import com.benton.izukijs.ui.common.IzukiPageScaffold
import com.benton.izukijs.ui.common.PageColumn
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.rememberAppContainer
import com.benton.izukijs.virtualscreen.VirtualScreenManager
import com.benton.izukijs.virtualscreen.VirtualScreenResolution
import com.benton.izukijs.virtualscreen.VirtualScreenState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 虚拟屏详情页：查看状态、启停、实时预览与触摸操作。 */
@Composable
fun VirtualScreenScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = container.virtualScreenManager
    val state by manager.state.collectAsStateWithLifecycle()
    val settings by container.virtualScreenSettingsRepository.settings.collectAsStateWithLifecycle()
    val scriptRunning by container.scriptExecutionManager.running.collectAsStateWithLifecycle()
    val runningScript by container.scriptExecutionManager.runningScript.collectAsStateWithLifecycle()
    val readyModes by container.controllerManager.readyModes.collectAsStateWithLifecycle()

    val active = state as? VirtualScreenState.Active
    val resolution = active?.resolution ?: settings.resolution
    val shizukuAvailable = ControlMode.SHIZUKU in readyModes && manager.available()
    val inputAvailable = container.controllerManager.controllerFor(Capability.SHELL) != null
    val interactive = active != null && !scriptRunning && inputAvailable
    val busy = state is VirtualScreenState.Starting

    var foregroundPackage by remember { mutableStateOf<String?>(null) }
    val foregroundLabel = remember(foregroundPackage) {
        foregroundPackage?.let { pkg ->
            runCatching {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                context.packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(pkg)
        }
    }

    var confirmStopScript by remember { mutableStateOf(false) }

    LaunchedEffect(active?.displayId) {
        if (active == null) {
            foregroundPackage = null
            return@LaunchedEffect
        }
        while (true) {
            foregroundPackage = manager.currentPackage()
            delay(2_000L)
        }
    }

    IzukiPageScaffold(
        title = { Text(stringResource(R.string.virtual_screen_page_title)) },
        onBack = onBack,
    ) { padding ->
        PageColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SectionCard(stringResource(R.string.virtual_screen_title)) {
                Text(
                    text = when (val current = state) {
                        VirtualScreenState.Idle -> stringResource(R.string.virtual_screen_status_idle)
                        VirtualScreenState.Starting -> stringResource(R.string.virtual_screen_status_starting)
                        is VirtualScreenState.Active -> stringResource(
                            R.string.virtual_screen_status_active,
                            current.displayId,
                            current.resolution.width,
                            current.resolution.height,
                            current.resolution.dpi,
                        )
                        is VirtualScreenState.Failed -> stringResource(
                            R.string.virtual_screen_status_failed,
                            current.message,
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state is VirtualScreenState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                if (active != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(
                            R.string.virtual_screen_current_app,
                            foregroundLabel ?: stringResource(R.string.virtual_screen_current_app_none),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (active == null) {
                        Button(
                            onClick = {
                                scope.launch { manager.start() }
                            },
                            enabled = !busy && shizukuAvailable && settings.backgroundAllowed,
                        ) { Text(stringResource(R.string.virtual_screen_start)) }
                    } else {
                        OutlinedButton(
                            onClick = {
                                scope.launch { manager.stop() }
                            },
                            enabled = !scriptRunning && !busy,
                        ) { Text(stringResource(R.string.virtual_screen_stop)) }
                    }
                }

                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.virtual_screen_status_starting),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (!shizukuAvailable) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.virtual_screen_shizuku_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onOpenSetup) {
                        Text(stringResource(R.string.virtual_screen_open_setup))
                    }
                } else if (!settings.backgroundAllowed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.virtual_screen_need_background),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOpenSettings) {
                        Text(stringResource(R.string.virtual_screen_open_settings))
                    }
                }
            }

            if (scriptRunning) {
                SectionCard(stringResource(R.string.virtual_screen_script_title)) {
                    Text(
                        stringResource(
                            R.string.virtual_screen_script_running,
                            runningScript ?: stringResource(R.string.virtual_screen_script_unknown),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.virtual_screen_script_running_touch_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { confirmStopScript = true }) {
                        Text(stringResource(R.string.virtual_screen_stop_script))
                    }
                }
            }

            SectionCard(stringResource(R.string.virtual_screen_preview_title)) {
                if (active != null) {
                    InteractivePreview(
                        resolution = resolution,
                        interactive = interactive,
                        scriptRunning = scriptRunning,
                        inputAvailable = inputAvailable,
                        onTap = { x, y -> scope.launch { manager.tap(x, y) } },
                        onLongPress = { x, y -> scope.launch { manager.longPress(x, y) } },
                        onSwipe = { x1, y1, x2, y2, duration ->
                            scope.launch { manager.swipe(x1, y1, x2, y2, duration) }
                        },
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.virtual_screen_preview_idle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        !inputAvailable -> stringResource(R.string.virtual_screen_input_unavailable)
                        scriptRunning -> stringResource(R.string.virtual_screen_script_running_touch_hint)
                        else -> stringResource(R.string.virtual_screen_touch_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmStopScript) {
        ConfirmDialog(
            title = stringResource(R.string.virtual_screen_stop_script),
            message = stringResource(R.string.virtual_screen_stop_script_confirm),
            destructive = true,
            onDismiss = { confirmStopScript = false },
            onConfirm = {
                container.scriptExecutionManager.requestStop()
                confirmStopScript = false
            },
        )
    }
}

@Composable
private fun InteractivePreview(
    resolution: VirtualScreenResolution,
    interactive: Boolean,
    scriptRunning: Boolean,
    inputAvailable: Boolean,
    onTap: (Float, Float) -> Unit,
    onLongPress: (Float, Float) -> Unit,
    onSwipe: (Float, Float, Float, Float, Long) -> Unit,
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val ratio = resolution.aspectRatio.coerceIn(MIN_PREVIEW_RATIO, MAX_PREVIEW_RATIO)
    val viewport = remember(viewSize, resolution) {
        VirtualScreenTouchMapper.viewport(
            viewWidth = viewSize.width.toFloat(),
            viewHeight = viewSize.height.toFloat(),
            screenWidth = resolution.width,
            screenHeight = resolution.height,
        )
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val previewHeight = minOf(maxWidth / ratio, MAX_PREVIEW_HEIGHT)
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
                owner = VirtualScreenManager.PAGE_PREVIEW_OWNER,
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            scriptRunning -> stringResource(R.string.virtual_screen_preview_readonly_script)
                            !inputAvailable -> stringResource(R.string.virtual_screen_preview_readonly_input)
                            else -> stringResource(R.string.virtual_screen_preview_readonly)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
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

private val MAX_PREVIEW_HEIGHT = 360.dp
private const val MIN_PREVIEW_RATIO = 0.5f
private const val MAX_PREVIEW_RATIO = 2.5f
