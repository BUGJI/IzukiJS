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

    val ui by viewModel.ui.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    // 只在前台时轮询前台包名；后台继续查询既费电也没有展示价值。
    LaunchedEffect(active?.displayId, lifecycleOwner) {
        if (active == null) {
            viewModel.updateForegroundPackage(null)
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                // Binder 查询放到 IO，避免每 2 秒在组合线程上做一次跨进程调用。
                viewModel.updateForegroundPackage(withContext(Dispatchers.IO) { manager.currentPackage() })
                delay(2_000L)
            }
        }
    }

    // 应用列表只在真正打开选择器时读取，避免进入虚拟屏页面就扫描全部可启动应用。
    LaunchedEffect(ui.showAppPicker, appListAllowed) {
        if (ui.showAppPicker && appListAllowed) viewModel.loadApps()
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
                VirtualScreenEvent.TextSent -> viewModel.setShowKeyboard(false)
            }
        }
    }

    LaunchedEffect(active) {
        if (active == null) viewModel.dismissTransientUi()
    }

    LaunchedEffect(interactive) {
        if (!interactive) viewModel.setShowKeyboard(false)
    }

    fun requestResolution(next: VirtualScreenResolution) {
        val normalized = next.normalized()
        val current = active?.resolution ?: settings.resolution
        if (normalized == current) return
        if (active != null) {
            viewModel.setResolutionToApply(normalized)
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
                    currentAppLabel = ui.currentAppLabel,
                    currentPackage = ui.foregroundPackage,
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
                    onStopScript = { viewModel.setConfirmStopScript(true) },
                    onChooseApp = { viewModel.setShowAppPicker(true) },
                    onKeyboard = { viewModel.setShowKeyboard(true) },
                    onKey = viewModel::sendKey,
                    onScreenshot = viewModel::saveScreenshot,
                    onOcr = viewModel::recognizeText,
                    onOpenSetup = onOpenSetup,
                    onOpenSettings = onOpenSettings,
                    onFullscreen = { viewModel.setShowFullscreen(true) },
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

    if (ui.showAppPicker && active != null) {
        AppPickerSheet(
            apps = appsState.apps,
            loading = appsState.loading,
            appListAllowed = appListAllowed,
            currentPackage = ui.foregroundPackage,
            launchingPackage = tools.launchingPackage,
            enabled = !scriptRunning,
            onRefresh = { viewModel.loadApps(force = true) },
            onSelect = { packageName ->
                // 先切换图标，避免等待下一次前台包名轮询。
                viewModel.updateForegroundPackage(packageName)
                viewModel.launchApp(packageName)
                viewModel.setShowAppPicker(false)
            },
            onOpenSetup = {
                viewModel.setShowAppPicker(false)
                onOpenSetup()
            },
            onDismiss = { viewModel.setShowAppPicker(false) },
        )
    }

    if (ui.showKeyboard && active != null) {
        KeyboardInputDialog(
            text = ui.keyboardDraft,
            onTextChange = { viewModel.setKeyboardDraft(it) },
            sending = tools.keyboardLoading,
            onSend = { viewModel.sendText(ui.keyboardDraft) },
            onDismiss = { viewModel.setShowKeyboard(false) },
        )
    }

    if (ui.showFullscreen && active != null) {
        FullscreenPreviewDialog(
            resolution = resolution,
            interactive = interactive,
            scriptRunning = scriptRunning,
            inputAvailable = inputAvailable,
            firstFrameArrived = tools.frameSeq > 0L,
            onDismiss = { viewModel.setShowFullscreen(false) },
            onTap = { x, y -> scope.launch { manager.tap(x, y) } },
            onLongPress = { x, y -> scope.launch { manager.longPress(x, y) } },
            onSwipe = { x1, y1, x2, y2, duration ->
                scope.launch { manager.swipe(x1, y1, x2, y2, duration) }
            },
        )
    }

    ui.resolutionToApply?.let { next ->
        ConfirmDialog(
            title = stringResource(R.string.virtual_screen_resolution_change_title),
            message = stringResource(R.string.virtual_screen_resolution_change_confirm),
            onDismiss = { viewModel.setResolutionToApply(null) },
            onConfirm = {
                viewModel.applyResolution(next)
                viewModel.setResolutionToApply(null)
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

    if (ui.confirmStopScript) {
        ConfirmDialog(
            title = stringResource(R.string.virtual_screen_stop_script),
            message = stringResource(R.string.virtual_screen_stop_script_confirm),
            destructive = true,
            onDismiss = { viewModel.setConfirmStopScript(false) },
            onConfirm = {
                viewModel.stopScript()
                viewModel.setConfirmStopScript(false)
            },
        )
    }
}

/** 宽屏下预览与控制分两栏的最小宽度；与主导航的 rail 断点保持一致。 */
private val PREVIEW_TWO_PANE_MIN_WIDTH = 600.dp

/** 宽屏页面最大内容宽度，给左右两栏留出足够空间。 */
private val WIDE_PAGE_MAX_WIDTH = 1000.dp

private val PHONE_PLACEHOLDER_HEIGHT = 180.dp
private val WIDE_PLACEHOLDER_HEIGHT = 220.dp
