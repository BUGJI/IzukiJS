package com.benton.izukijs.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.controller.record.AppEvent
import com.benton.izukijs.controller.record.ClickEvent
import com.benton.izukijs.controller.record.ContextEvent
import com.benton.izukijs.controller.record.GestureEvent
import com.benton.izukijs.controller.record.GlobalActionEvent
import com.benton.izukijs.controller.record.InputTextEvent
import com.benton.izukijs.controller.record.LongClickEvent
import com.benton.izukijs.controller.record.PressKeyEvent
import com.benton.izukijs.controller.record.RecordEvent
import com.benton.izukijs.controller.record.RecordingMeta
import com.benton.izukijs.controller.record.RecordingReader
import com.benton.izukijs.controller.record.RecordingScriptExporter
import com.benton.izukijs.controller.record.RecordingValidation
import com.benton.izukijs.controller.record.ScreenshotEvent
import com.benton.izukijs.controller.record.SessionStartEvent
import com.benton.izukijs.controller.record.ShellEvent
import com.benton.izukijs.controller.record.SwipeEvent
import com.benton.izukijs.controller.record.UserActionEvent
import com.benton.izukijs.controller.record.UserActionKind
import com.benton.izukijs.ui.appViewModel
import com.benton.izukijs.ui.common.LabeledField
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SectionGap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordingDetailPage(id: String, onBack: () -> Unit) {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val settingsState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarController.current
    val context = LocalContext.current
    val resources = LocalResources.current
    var reader by remember(id) { mutableStateOf<RecordingReader?>(null) }
    var loading by remember(id) { mutableStateOf(true) }
    var loadFailed by remember(id) { mutableStateOf(false) }
    var reloadToken by remember(id) { mutableIntStateOf(0) }
    var visibleCount by remember(id) { mutableIntStateOf(MAX_ROWS) }
    var renameOpen by rememberSaveable(id) { mutableStateOf(false) }
    var nameDraft by rememberSaveable(id) { mutableStateOf("") }
    var validation by remember(id) { mutableStateOf<RecordingValidation?>(null) }
    var validating by remember(id) { mutableStateOf(false) }
    var creatingScript by remember(id) { mutableStateOf(false) }
    val activeRecording = settingsState.recordingActive && viewModel.currentRecordingId() == id
    var wasActiveRecording by remember(id) { mutableStateOf(activeRecording) }

    // 录制结束后自动重新读取一次，让详情页从“可能不完整”切换到完整时间线。
    LaunchedEffect(activeRecording) {
        if (wasActiveRecording && !activeRecording) reloadToken++
        wasActiveRecording = activeRecording
    }

    LaunchedEffect(id, reloadToken) {
        loading = true
        loadFailed = false
        validation = null
        try {
            val loaded = viewModel.loadRecording(id)
            reader = loaded
            if (!renameOpen) nameDraft = loaded?.meta?.name.orEmpty()
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            reader = null
            loadFailed = true
        } finally {
            loading = false
        }
    }
    val activeReader = reader
    DisposableEffect(activeReader) {
        onDispose { activeReader?.clearFrameCache() }
    }

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.record_detail_back)) }
            Spacer(Modifier.width(8.dp))
            Text(
                reader?.meta?.name ?: id,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        val current = reader
        if (loading) {
            Text(
                stringResource(R.string.record_detail_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        if (current == null) {
            Text(
                stringResource(
                    if (loadFailed) R.string.record_page_load_failed
                    else R.string.record_detail_missing,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            if (loadFailed) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { reloadToken++ }) {
                    Text(stringResource(R.string.common_refresh))
                }
            }
            return@SectionCard
        }
        if (activeRecording) {
            Text(
                stringResource(R.string.record_detail_active_readonly),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            stringResource(
                R.string.record_detail_meta,
                if (current.meta.kind == "user") stringResource(R.string.record_page_user_kind)
                else stringResource(R.string.record_page_script_kind),
                current.meta.eventCount,
                current.meta.frameCount,
                current.meta.durationMs,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(
                R.string.record_detail_extra,
                current.meta.inputSource.ifBlank { "—" },
                stringResource(
                    if (current.meta.framesAvailable) R.string.record_detail_frames_yes
                    else R.string.record_detail_frames_no,
                ),
                recordingStatus(current.meta),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val script = withContext(Dispatchers.IO) {
                            RecordingScriptExporter.build(current)
                        }
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("recording-script", script))
                        snackbar.show(resources.getString(R.string.record_detail_script_copied))
                    }
                },
                enabled = !activeRecording,
            ) { Text(stringResource(R.string.record_detail_export_script)) }
            OutlinedButton(
                onClick = {
                    creatingScript = true
                    scope.launch {
                        val created = try {
                            val name = resources.getString(
                                R.string.record_detail_script_name,
                                current.meta.name,
                            )
                            viewModel.createScriptFromRecording(id, name)
                        } catch (t: Throwable) {
                            null
                        } finally {
                            creatingScript = false
                        }
                        snackbar.show(
                            if (created != null) {
                                resources.getString(
                                    R.string.record_detail_script_created,
                                    created.name,
                                )
                            } else {
                                resources.getString(R.string.record_detail_script_create_failed)
                            },
                        )
                    }
                },
                enabled = !activeRecording && !creatingScript,
            ) {
                Text(
                    stringResource(
                        if (creatingScript) R.string.record_detail_script_creating
                        else R.string.record_detail_export_to_script,
                    ),
                )
            }
            OutlinedButton(
                onClick = {
                    validating = true
                    scope.launch {
                        validation = try {
                            viewModel.validateRecording(id)
                        } catch (t: Throwable) {
                            null
                        } finally {
                            validating = false
                        }
                        if (validation == null) {
                            snackbar.show(resources.getString(R.string.record_detail_validation_missing))
                        }
                    }
                },
                enabled = !activeRecording && !validating,
            ) {
                Text(
                    stringResource(
                        if (validating) R.string.record_detail_validating
                        else R.string.record_detail_validation,
                    ),
                )
            }
            OutlinedButton(
                onClick = { renameOpen = true },
                enabled = !activeRecording,
            ) {
                Text(stringResource(R.string.record_detail_rename))
            }
            OutlinedButton(onClick = { reloadToken++ }) {
                Text(stringResource(R.string.common_refresh))
            }
        }
    }

    val currentValidation = validation
    if (currentValidation != null) {
        Spacer(Modifier.height(SectionGap))
        SectionCard {
            Text(
                if (currentValidation.ok) {
                    stringResource(R.string.record_detail_validation_ok)
                } else {
                    stringResource(
                        R.string.record_detail_validation_failed,
                        currentValidation.issues.size,
                    )
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (currentValidation.ok) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            if (currentValidation.issues.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                currentValidation.issues.forEach { issue ->
                    Text(
                        "• $issue",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text(stringResource(R.string.record_detail_rename_title)) },
            text = {
                LabeledField(
                    label = stringResource(R.string.record_page_name),
                    value = nameDraft,
                    onChange = { nameDraft = it },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renameOpen = false
                        scope.launch {
                            val ok = viewModel.renameRecording(id, nameDraft)
                            // 先刷新再提示：snackbar.show 可能挂起到提示消失，放在后面会让标题延迟更新。
                            if (ok) reloadToken++
                            snackbar.show(
                                resources.getString(
                                    if (ok) R.string.record_detail_renamed
                                    else R.string.record_detail_rename_failed,
                                ),
                            )
                        }
                    },
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Spacer(Modifier.height(SectionGap))
    val current = reader ?: return
    val actions = remember(current) {
        current.events.filter {
            it !is ScreenshotEvent && it !is ContextEvent && it !is SessionStartEvent
        }
    }
    if (actions.isEmpty()) {
        SectionCard {
            Text(
                stringResource(R.string.record_detail_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val screenshots = remember(current) { current.events.filterIsInstance<ScreenshotEvent>() }
    val framesByPath = remember(screenshots) { screenshots.associateBy { it.frame } }
    SectionCard {
        actions.take(visibleCount).forEachIndexed { index, event ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            RecordingEventRow(
                reader = current,
                event = event,
                screenshot = screenshotBefore(event, screenshots, framesByPath),
                screenshotAfter = screenshotAfter(event, framesByPath),
            )
        }
        val remaining = actions.size - visibleCount
        if (remaining > 0) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { visibleCount += MAX_ROWS },
            ) {
                Text(
                    stringResource(R.string.record_detail_more, remaining) + " · " +
                        stringResource(R.string.record_detail_load_more),
                )
            }
        }
    }
}

private fun screenshotBefore(
    event: RecordEvent,
    screenshots: List<ScreenshotEvent>,
    framesByPath: Map<String, ScreenshotEvent>,
): ScreenshotEvent? {
    if (event is UserActionEvent) {
        return event.beforeFrame?.let(framesByPath::get)
    }
    return screenshots.lastOrNull { it.timestamp <= event.timestamp }
}

private fun screenshotAfter(
    event: RecordEvent,
    framesByPath: Map<String, ScreenshotEvent>,
): ScreenshotEvent? {
    val action = event as? UserActionEvent ?: return null
    return action.afterFrame?.let(framesByPath::get)
}

@Composable
private fun recordingStatus(meta: RecordingMeta): String = when {
    !meta.complete -> stringResource(R.string.record_detail_status_interrupted)
    meta.truncated -> stringResource(R.string.record_detail_status_truncated)
    else -> stringResource(R.string.record_detail_status_ok)
}

@Composable
private fun RecordingEventRow(
    reader: RecordingReader,
    event: RecordEvent,
    screenshot: ScreenshotEvent?,
    screenshotAfter: ScreenshotEvent?,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (screenshot != null || screenshotAfter != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (screenshot != null) {
                    RecordingThumbnail(reader = reader, screenshot = screenshot)
                    Text(
                        stringResource(R.string.record_detail_frame_before),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (screenshotAfter != null && screenshotAfter.frame != screenshot?.frame) {
                    RecordingThumbnail(reader = reader, screenshot = screenshotAfter)
                    Text(
                        stringResource(R.string.record_detail_frame_after),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "${formatTime(event.timestamp)} · ${eventTitle(event)}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            eventSubtitle(event)?.let { subtitle ->
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (screenshot?.stale == true) {
                Text(
                    stringResource(R.string.record_detail_stale_frame),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RecordingThumbnail(reader: RecordingReader, screenshot: ScreenshotEvent) {
    val bitmap by produceState<Bitmap?>(initialValue = null, reader, screenshot) {
        // 按缩略图尺寸采样解码，避免详情页同时持有大量全尺寸帧。
        value = withContext(Dispatchers.IO) { reader.frame(screenshot, THUMB_MAX_DIM) }
    }
    // 不要 recycle：Compose 的 Image 可能仍在绘制同一 Bitmap，回收会触发
    // "Canvas: trying to use a recycled bitmap"。让 GC 负责这些缩略图副本。
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(width = 64.dp, height = 112.dp)
                .padding(1.dp),
        )
    } else {
        Spacer(Modifier.size(width = 64.dp, height = 112.dp))
    }
}

@Composable
private fun eventTitle(event: RecordEvent): String = when (event) {
    is UserActionEvent -> when (event.kind) {
        UserActionKind.TAP -> event.targets.firstOrNull { !it.text.isNullOrBlank() }?.text
            ?.takeIf { it.isNotBlank() }
            ?.let { stringResource(R.string.record_event_tap_with_text, it) }
            ?: stringResource(R.string.record_event_tap)
        UserActionKind.LONG_CLICK -> stringResource(R.string.record_event_long_click)
        UserActionKind.SWIPE -> stringResource(R.string.record_event_swipe)
        UserActionKind.GESTURE -> stringResource(R.string.record_event_gesture)
        UserActionKind.MULTI_SWIPE -> stringResource(R.string.record_event_multi_swipe)
        UserActionKind.KEY -> stringResource(R.string.record_event_key)
    }
    is ClickEvent -> stringResource(R.string.record_event_script_click)
    is LongClickEvent -> stringResource(R.string.record_event_script_long_click)
    is SwipeEvent -> stringResource(R.string.record_event_script_swipe)
    is GestureEvent -> stringResource(R.string.record_event_script_gesture)
    is PressKeyEvent -> stringResource(R.string.record_event_script_key, event.keyCode)
    is InputTextEvent -> stringResource(R.string.record_event_input)
    is GlobalActionEvent -> stringResource(R.string.record_event_global_action, event.action)
    is ShellEvent -> stringResource(R.string.record_event_shell, event.cmd)
    is AppEvent -> stringResource(R.string.record_event_app, event.packageName)
    is ScreenshotEvent -> stringResource(R.string.record_event_screenshot)
    is ContextEvent -> stringResource(R.string.record_event_context)
    is SessionStartEvent -> stringResource(R.string.record_event_session_start)
}

@Composable
private fun eventSubtitle(event: RecordEvent): String? = when (event) {
    is UserActionEvent -> buildString {
        if (event.packageName.isNotBlank()) append(event.packageName).append(" · ")
        append("(${event.x}, ${event.y})")
        if (event.pointerCount > 1) {
            append(" · ").append(stringResource(R.string.record_event_pointers, event.pointerCount))
        }
        event.targets.firstOrNull()?.viewId?.takeIf { it.isNotBlank() }?.let {
            append(" · ").append(it)
        }
    }
    is ClickEvent -> "(${event.x}, ${event.y})"
    is LongClickEvent -> "(${event.x}, ${event.y}) · ${event.durationMs}ms"
    is SwipeEvent -> "(${event.x1}, ${event.y1}) → (${event.x2}, ${event.y2})"
    is InputTextEvent -> event.text
    is AppEvent -> event.action
    else -> null
}

private fun formatTime(timestamp: Long): String {
    val seconds = timestamp / 1000.0
    return String.format(Locale.US, "%.2fs", seconds)
}

private const val MAX_ROWS = 120
private const val THUMB_MAX_DIM = 360
