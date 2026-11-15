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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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

/** 虚拟屏控制台：实时预览、应用启动、快捷按键、截图 / OCR 与诊断。 */
@Composable
fun VirtualScreenScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = LocalSnackbarController.current
    val scope = rememberCoroutineScope()
    val manager = container.virtualScreenManager
    val viewModel: VirtualScreenViewModel = appViewModel { VirtualScreenViewModel(it) }

    val state by manager.state.collectAsStateWithLifecycle()
    val settings by container.virtualScreenSettingsRepository.settings.collectAsStateWithLifecycle()
    val runtimeSettings by container.runtimeSettingsRepository.settings.collectAsStateWithLifecycle()
    val scriptRunning by container.scriptExecutionManager.running.collectAsStateWithLifecycle()
    val runningScript by container.scriptExecutionManager.runningScript.collectAsStateWithLifecycle()
    val readyModes by container.controllerManager.readyModes.collectAsStateWithLifecycle()
    val previewAttached by manager.previewAttached.collectAsStateWithLifecycle()
    val appsState by viewModel.apps.collectAsStateWithLifecycle()
    val tools by viewModel.tools.collectAsStateWithLifecycle()

    val active = state as? VirtualScreenState.Active
    val resolution = active?.resolution ?: settings.resolution
    val shizukuAvailable = ControlMode.SHIZUKU in readyModes && manager.available()
    val inputAvailable = container.controllerManager.controllerFor(Capability.SHELL) != null
    val interactive = active != null && !scriptRunning && inputAvailable
    val busy = state is VirtualScreenState.Starting
    val appListAllowed = runtimeSettings.appList
    val deviceResolution = remember(context, LocalConfiguration.current) {
        DeviceScreenResolution.read(context)
    }
    val presets = remember(deviceResolution) {
        buildList {
            add(deviceResolution)
            VirtualScreenResolution.presets.forEach { if (it != deviceResolution) add(it) }
        }
    }

    var foregroundPackage by remember { mutableStateOf<String?>(null) }
    val currentAppLabel by produceState<String?>(initialValue = null, foregroundPackage) {
        value = foregroundPackage?.let { pkg ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val info = context.packageManager.getApplicationInfo(pkg, 0)
                    context.packageManager.getApplicationLabel(info).toString()
                }.getOrDefault(pkg)
            }
        }
    }

    var confirmStopScript by remember { mutableStateOf(false) }
    var showFullscreen by rememberSaveable { mutableStateOf(false) }
    var showAppPicker by rememberSaveable { mutableStateOf(false) }
    var showKeyboard by rememberSaveable { mutableStateOf(false) }
    var keyboardDraft by rememberSaveable { mutableStateOf("") }
    var resolutionToApply by remember { mutableStateOf<VirtualScreenResolution?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    // 只在前台时轮询前台包名；后台继续查询既费电也没有展示价值。
    LaunchedEffect(active?.displayId, lifecycleOwner) {
        if (active == null) {
            foregroundPackage = null
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                // Binder 查询放到 IO，避免每 2 秒在组合线程上做一次跨进程调用。
                foregroundPackage = withContext(Dispatchers.IO) { manager.currentPackage() }
                delay(2_000L)
            }
        }
    }

    // 应用列表只在真正打开选择器时读取，避免进入虚拟屏页面就扫描全部可启动应用。
    LaunchedEffect(showAppPicker, appListAllowed) {
        if (showAppPicker && appListAllowed) viewModel.loadApps()
    }

    DisposableEffect(viewModel) {
        viewModel.startDiagnostics()
        onDispose { viewModel.stopDiagnostics() }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is VirtualScreenEvent.Message -> snackbar.show(event.text)
                is VirtualScreenEvent.ScreenshotSaved -> {
                    val result = snackbar.show(
                        message = resources.getString(R.string.virtual_screen_screenshot_saved),
                        actionLabel = resources.getString(R.string.virtual_screen_share),
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        shareUri(
                            context = context,
                            uri = event.uri,
                            chooserTitle = resources.getString(R.string.virtual_screen_share_title),
                            mimeType = "image/png",
                        )
                    }
                }
                VirtualScreenEvent.TextSent -> {
                    showKeyboard = false
                    keyboardDraft = ""
                }
            }
        }
    }

    LaunchedEffect(active) {
        if (active == null) {
            showFullscreen = false
            showAppPicker = false
            showKeyboard = false
            keyboardDraft = ""
        }
    }

    LaunchedEffect(interactive) {
        if (!interactive) showKeyboard = false
    }

    fun requestResolution(next: VirtualScreenResolution) {
        val normalized = next.normalized()
        val current = active?.resolution ?: settings.resolution
        if (normalized == current) return
        if (active != null) {
            resolutionToApply = normalized
        } else {
            viewModel.applyResolution(normalized)
        }
    }

    fun copyDiagnostics() {
        val text = diagnosticText(
            state = state,
            active = active,
            resolution = resolution,
            frameSeq = tools.frameSeq,
            target = manager.target(),
            resolvedTarget = manager.resolvedTarget(),
            pipAttached = previewAttached,
            lastStopReason = tools.lastStopReason,
            lastStopAt = tools.lastStopAt,
        )
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("virtual_screen", text))
        scope.launch { snackbar.show(resources.getString(R.string.virtual_screen_diag_copied)) }
    }

    IzukiPageScaffold(
        title = { Text(stringResource(R.string.virtual_screen_page_title)) },
        onBack = onBack,
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val wide = maxWidth >= PREVIEW_TWO_PANE_MIN_WIDTH
            val previewSection: @Composable () -> Unit = {
                PreviewCard(
                    state = state,
                    resolution = resolution,
                    currentAppLabel = currentAppLabel,
                    currentPackage = foregroundPackage,
                    launchingApp = tools.launchingPackage != null,
                    scriptRunning = scriptRunning,
                    runningScript = runningScript,
                    inputAvailable = inputAvailable,
                    interactive = interactive,
                    firstFrameArrived = tools.frameSeq > 0L,
                    shizukuAvailable = shizukuAvailable,
                    backgroundAllowed = settings.backgroundAllowed,
                    busy = busy,
                    wide = wide,
                    screenshotLoading = tools.screenshotLoading,
                    ocrLoading = tools.ocrLoading,
                    onStart = viewModel::start,
                    onStop = viewModel::stop,
                    onStopScript = { confirmStopScript = true },
                    onChooseApp = { showAppPicker = true },
                    onKeyboard = { showKeyboard = true },
                    onKey = viewModel::sendKey,
                    onScreenshot = viewModel::saveScreenshot,
                    onOcr = viewModel::recognizeText,
                    onOpenSetup = onOpenSetup,
                    onOpenSettings = onOpenSettings,
                    onFullscreen = { showFullscreen = true },
                    onTap = { x, y -> scope.launch { manager.tap(x, y) } },
                    onLongPress = { x, y -> scope.launch { manager.longPress(x, y) } },
                    onSwipe = { x1, y1, x2, y2, duration ->
                        scope.launch { manager.swipe(x1, y1, x2, y2, duration) }
                    },
                )
            }
            val controlsSection: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(SectionGap)) {
                    DisplayCard(
                        resolution = resolution,
                        presets = presets,
                        deviceResolution = deviceResolution,
                        busy = busy,
                        scriptRunning = scriptRunning,
                        onSelect = ::requestResolution,
                        onOpenSettings = onOpenSettings,
                    )

                    DiagnosticsCard(
                        state = state,
                        active = active,
                        resolution = resolution,
                        frameSeq = tools.frameSeq,
                        target = manager.target(),
                        resolvedTarget = manager.resolvedTarget(),
                        pipAttached = previewAttached,
                        lastStopReason = tools.lastStopReason,
                        lastStopAt = tools.lastStopAt,
                        onCopy = ::copyDiagnostics,
                    )
                }
            }
            PageColumn(
                modifier = Modifier.padding(horizontal = 16.dp),
                maxWidth = if (wide) WIDE_PAGE_MAX_WIDTH else PageMaxWidth,
                verticalArrangement = Arrangement.spacedBy(SectionGap),
            ) {
                Spacer(Modifier.height(4.dp))
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SectionGap),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) { previewSection() }
                        Column(modifier = Modifier.weight(1f)) { controlsSection() }
                    }
                } else {
                    previewSection()
                    controlsSection()
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showAppPicker && active != null) {
        AppPickerSheet(
            apps = appsState.apps,
            loading = appsState.loading,
            appListAllowed = appListAllowed,
            currentPackage = foregroundPackage,
            launchingPackage = tools.launchingPackage,
            enabled = !scriptRunning,
            onRefresh = { viewModel.loadApps(force = true) },
            onSelect = { packageName ->
                // 先切换图标，避免等待下一次前台包名轮询。
                foregroundPackage = packageName
                viewModel.launchApp(packageName)
                showAppPicker = false
            },
            onOpenSetup = {
                showAppPicker = false
                onOpenSetup()
            },
            onDismiss = { showAppPicker = false },
        )
    }

    if (showKeyboard && active != null) {
        KeyboardInputDialog(
            text = keyboardDraft,
            onTextChange = { keyboardDraft = it },
            sending = tools.keyboardLoading,
            onSend = { viewModel.sendText(keyboardDraft) },
            onDismiss = { showKeyboard = false },
        )
    }

    if (showFullscreen && active != null) {
        FullscreenPreviewDialog(
            resolution = resolution,
            interactive = interactive,
            scriptRunning = scriptRunning,
            inputAvailable = inputAvailable,
            firstFrameArrived = tools.frameSeq > 0L,
            onDismiss = { showFullscreen = false },
            onTap = { x, y -> scope.launch { manager.tap(x, y) } },
            onLongPress = { x, y -> scope.launch { manager.longPress(x, y) } },
            onSwipe = { x1, y1, x2, y2, duration ->
                scope.launch { manager.swipe(x1, y1, x2, y2, duration) }
            },
        )
    }

    resolutionToApply?.let { next ->
        ConfirmDialog(
            title = stringResource(R.string.virtual_screen_resolution_change_title),
            message = stringResource(R.string.virtual_screen_resolution_change_confirm),
            onDismiss = { resolutionToApply = null },
            onConfirm = {
                viewModel.applyResolution(next)
                resolutionToApply = null
            },
        )
    }

    tools.ocrText?.let { text ->
        OcrResultDialog(
            text = text,
            onDismiss = viewModel::clearOcr,
            onCopy = {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("virtual_screen_ocr", text))
                viewModel.clearOcr()
                scope.launch { snackbar.show(resources.getString(R.string.virtual_screen_ocr_copied)) }
            },
        )
    }

    if (confirmStopScript) {
        ConfirmDialog(
            title = stringResource(R.string.virtual_screen_stop_script),
            message = stringResource(R.string.virtual_screen_stop_script_confirm),
            destructive = true,
            onDismiss = { confirmStopScript = false },
            onConfirm = {
                viewModel.stopScript()
                confirmStopScript = false
            },
        )
    }
}

@Composable
private fun PreviewCard(
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
private fun InteractiveStage(
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

@Composable
private fun DisplayCard(
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
private fun DiagnosticsCard(
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

private fun diagnosticText(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(
    apps: List<VirtualScreenApp>,
    loading: Boolean,
    appListAllowed: Boolean,
    currentPackage: String?,
    launchingPackage: String?,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenSetup: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(apps, query) { filterVirtualScreenApps(apps, query) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.virtual_screen_apps_sheet_title),
                style = MaterialTheme.typography.titleLarge,
            )
            if (!appListAllowed) {
                Text(
                    stringResource(R.string.virtual_screen_apps_disabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenSetup) {
                    Text(stringResource(R.string.virtual_screen_open_setup))
                }
                return@Column
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                        }
                    }
                },
                placeholder = { Text(stringResource(R.string.virtual_screen_apps_search)) },
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (loading) {
                        stringResource(R.string.virtual_screen_apps_loading)
                    } else {
                        stringResource(R.string.virtual_screen_apps_count, visible.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRefresh, enabled = !loading) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.virtual_screen_apps_refresh))
                }
            }

            when {
                loading && apps.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.virtual_screen_apps_loading))
                }

                visible.isEmpty() -> Text(
                    stringResource(R.string.virtual_screen_apps_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) {
                    items(visible, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            current = app.packageName == currentPackage,
                            launching = launchingPackage == app.packageName,
                            enabled = enabled && launchingPackage == null,
                            onSelect = onSelect,
                        )
                    }
                }
            }

            Text(
                stringResource(R.string.virtual_screen_apps_launch_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppRow(
    app: VirtualScreenApp,
    current: Boolean,
    launching: Boolean,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onSelect(app.packageName) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(packageName = app.packageName, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (app.isSystem) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.virtual_screen_apps_system),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        when {
            launching -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )

            current -> Text(
                stringResource(R.string.virtual_screen_apps_current),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 应用图标解码后可跨行 / 滚动复用，避免列表回收时重复查 PackageManager。 */
private val appIconCache = object : LruCache<String, ImageBitmap>(64) {}

@Composable
private fun AppIcon(packageName: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(
        initialValue = packageName?.let { appIconCache.get(it) },
        packageName,
    ) {
        val pkg = packageName ?: return@produceState
        value = appIconCache.get(pkg) ?: withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(pkg)
                    .toBitmap(96, 96)
                    .asImageBitmap()
            }.getOrNull()?.also { appIconCache.put(pkg, it) }
        }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.clip(RoundedCornerShape(8.dp)),
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Apps,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize(0.6f),
            )
        }
    }
}

@Composable
private fun KeyboardInputDialog(
    text: String,
    onTextChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // 弹窗刚进入组合时可能尚未附加到窗口，失败时让用户手动点输入框即可。
        runCatching { focusRequester.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(stringResource(R.string.virtual_screen_keyboard_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.virtual_screen_keyboard_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    placeholder = {
                        Text(stringResource(R.string.virtual_screen_keyboard_placeholder))
                    },
                    minLines = 3,
                    maxLines = 5,
                    enabled = !sending,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSend, enabled = text.isNotBlank() && !sending) {
                if (sending) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.virtual_screen_keyboard_send))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun OcrResultDialog(
    text: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.virtual_screen_ocr_title)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onCopy) {
                Text(stringResource(R.string.virtual_screen_ocr_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
    )
}

@Composable
private fun FullscreenPreviewDialog(
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

/** 宽屏下预览与控制分两栏的最小宽度；与主导航的 rail 断点保持一致。 */
private val PREVIEW_TWO_PANE_MIN_WIDTH = 600.dp

/** 宽屏页面最大内容宽度，给左右两栏留出足够空间。 */
private val WIDE_PAGE_MAX_WIDTH = 1000.dp

private val PHONE_PLACEHOLDER_HEIGHT = 180.dp
private val WIDE_PLACEHOLDER_HEIGHT = 220.dp
