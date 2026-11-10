package com.benton.izukijs.ui.settings

import com.benton.izukijs.ui.common.SectionGap
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.model.RunMode
import com.benton.izukijs.runtime.FloatingWindowMode
import com.benton.izukijs.service.DebugOverlayService
import com.benton.izukijs.service.FloatingWindowService
import com.benton.izukijs.ui.pip.PipController
import com.benton.izukijs.virtualscreen.DeviceScreenResolution
import com.benton.izukijs.virtualscreen.VirtualScreenResolution
import com.benton.izukijs.virtualscreen.VirtualScreenState
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.LabeledField
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SwitchRow
import com.benton.izukijs.ui.common.localizedName
import com.benton.izukijs.ui.appViewModel

/** 悬浮窗与虚拟屏：统一管理悬浮条显示方式、控制面板开关与虚拟屏参数。 */
@Composable
internal fun DisplaySettings(onOpenSetup: () -> Unit = {}) {
    FloatingWindowSettingsSection()

    Spacer(Modifier.height(SectionGap))
    VirtualScreenSettingsSection(onOpenSetup = onOpenSetup)
}

@Composable
private fun FloatingWindowSettingsSection() {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val floatingEnabled by FloatingWindowService.active.collectAsStateWithLifecycle()
    val debugOverlayEnabled by DebugOverlayService.active.collectAsStateWithLifecycle()
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var pendingGrant by remember { mutableStateOf<(() -> Unit)?>(null) }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(context)) {
            canDrawOverlays = true
            val action = pendingGrant
            pendingGrant = null
            action?.invoke()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canDrawOverlays = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun withOverlay(onGranted: () -> Unit) {
        if (Settings.canDrawOverlays(context)) {
            onGranted()
            return
        }
        pendingGrant = onGranted
        runCatching {
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun saveFloatingMode(mode: FloatingWindowMode) {
        viewModel.saveRuntimeSettings(ui.runtimeSettings.copy(floatingWindowMode = mode))
        if (mode == FloatingWindowMode.OFF) {
            FloatingWindowService.stop(context)
        }
    }

    SectionCard(stringResource(R.string.settings_floating_window)) {
        Text(
            stringResource(R.string.settings_floating_window_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ChipFlow {
            FloatingWindowMode.entries.forEach { mode ->
                FilterChip(
                    selected = ui.runtimeSettings.floatingWindowMode == mode,
                    onClick = { saveFloatingMode(mode) },
                    label = { Text(mode.localizedName()) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.settings_floating_window_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(SectionGap))
        SwitchRow(
            label = stringResource(R.string.perm_floating_control),
            checked = floatingEnabled,
            onChange = { checked ->
                if (checked) {
                    withOverlay { FloatingWindowService.start(context) }
                } else {
                    FloatingWindowService.stop(context)
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        SwitchRow(
            label = stringResource(R.string.perm_floating_debug),
            checked = debugOverlayEnabled,
            description = stringResource(R.string.perm_floating_debug_desc),
            onChange = { checked ->
                if (checked) {
                    withOverlay { DebugOverlayService.start(context) }
                } else {
                    DebugOverlayService.stop(context)
                }
            },
        )
        if (!canDrawOverlays) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.perm_overlay_needed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VirtualScreenSettingsSection(onOpenSetup: () -> Unit = {}) {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settings = ui.virtualScreenSettings
    val readyModes = ui.readyModes
    val state = ui.virtualScreenState
    val shizukuReady = ControlMode.SHIZUKU in readyModes && viewModel.virtualScreenAvailable()
    val pipSupported = PipController.isSupported(context)
    val deviceResolution = remember(context) { DeviceScreenResolution.read(context) }
    val resolutionPresets = buildList {
        add(deviceResolution)
        VirtualScreenResolution.presets.forEach { if (it != deviceResolution) add(it) }
    }

    fun save(new: com.benton.izukijs.virtualscreen.VirtualScreenSettings) =
        viewModel.saveVirtualScreen(new)

    var widthText by remember { mutableStateOf(settings.resolution.width.toString()) }
    var heightText by remember { mutableStateOf(settings.resolution.height.toString()) }
    var dpiText by remember { mutableStateOf(settings.resolution.dpi.toString()) }
    LaunchedEffect(settings.resolution) {
        widthText = settings.resolution.width.toString()
        heightText = settings.resolution.height.toString()
        dpiText = settings.resolution.dpi.toString()
    }
    val customSizeInvalid =
        widthText.toIntOrNull()?.let { it in VirtualScreenResolution.MIN_SIZE..VirtualScreenResolution.MAX_SIZE } != true ||
            heightText.toIntOrNull()?.let { it in VirtualScreenResolution.MIN_SIZE..VirtualScreenResolution.MAX_SIZE } != true ||
            dpiText.toIntOrNull()?.let { it in VirtualScreenResolution.MIN_DPI..VirtualScreenResolution.MAX_DPI } != true

    SectionCard(stringResource(R.string.virtual_screen_title)) {
        Text(
            stringResource(R.string.virtual_screen_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(SectionGap))
        SwitchRow(
            label = stringResource(R.string.virtual_screen_allow_background),
            description = stringResource(
                if (shizukuReady) {
                    R.string.virtual_screen_allow_background_desc
                } else {
                    R.string.virtual_screen_shizuku_required
                },
            ),
            checked = settings.backgroundAllowed,
            enabled = shizukuReady || settings.backgroundAllowed,
        ) { checked ->
            val modes = if (checked) {
                settings.allowedModes + RunMode.BACKGROUND
            } else {
                settings.allowedModes - RunMode.BACKGROUND
            }
            val default = if (!checked && settings.defaultMode == RunMode.BACKGROUND) {
                RunMode.FOREGROUND
            } else {
                settings.defaultMode
            }
            save(settings.copy(allowedModes = modes, defaultMode = default))
        }
        if (!shizukuReady) {
            TextButton(onClick = onOpenSetup) {
                Text(stringResource(R.string.virtual_screen_open_setup))
            }
        }

        Spacer(Modifier.height(SectionGap))
        Text(
            stringResource(R.string.virtual_screen_default_mode),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(4.dp))
        ChipFlow {
            RunMode.entries.filter { it in settings.allowedModes }.forEach { mode ->
                FilterChip(
                    selected = settings.defaultMode == mode,
                    onClick = { save(settings.copy(defaultMode = mode)) },
                    label = { Text(mode.localizedName()) },
                )
            }
        }

        if (settings.backgroundAllowed) {
            Spacer(Modifier.height(SectionGap))
            Text(
                stringResource(R.string.virtual_screen_resolution),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            ChipFlow {
                resolutionPresets.forEach { preset ->
                    FilterChip(
                        selected = settings.resolution == preset,
                        onClick = { save(settings.copy(resolution = preset)) },
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
            Text(
                stringResource(
                    R.string.virtual_screen_device_preset_desc,
                    deviceResolution.width,
                    deviceResolution.height,
                    deviceResolution.dpi,
                    VirtualScreenResolution.DEVICE_MAX_LONG_SIDE,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.virtual_screen_custom_size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LabeledField(
                label = stringResource(R.string.virtual_screen_width),
                value = widthText,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            ) { input ->
                widthText = input
                input.toIntOrNull()?.let { width ->
                    val next = settings.resolution.copy(width = width)
                    if (next.isValid) save(settings.copy(resolution = next))
                }
            }
            LabeledField(
                label = stringResource(R.string.virtual_screen_height),
                value = heightText,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            ) { input ->
                heightText = input
                input.toIntOrNull()?.let { height ->
                    val next = settings.resolution.copy(height = height)
                    if (next.isValid) save(settings.copy(resolution = next))
                }
            }
            LabeledField(
                label = stringResource(R.string.virtual_screen_dpi),
                value = dpiText,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            ) { input ->
                dpiText = input
                input.toIntOrNull()?.let { dpi ->
                    val next = settings.resolution.copy(dpi = dpi)
                    if (next.isValid) save(settings.copy(resolution = next))
                }
            }
            Text(
                stringResource(
                    R.string.virtual_screen_range,
                    VirtualScreenResolution.MIN_SIZE,
                    VirtualScreenResolution.MAX_SIZE,
                    VirtualScreenResolution.MIN_DPI,
                    VirtualScreenResolution.MAX_DPI,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (customSizeInvalid) {
                Text(
                    stringResource(R.string.virtual_screen_custom_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (pipSupported) {
                Spacer(Modifier.height(SectionGap))
                SwitchRow(
                    label = stringResource(R.string.virtual_screen_pip_on_home),
                    description = stringResource(R.string.virtual_screen_pip_desc),
                    checked = settings.pipOnHome,
                ) { save(settings.copy(pipOnHome = it)) }
            }

            Spacer(Modifier.height(SectionGap))
            SwitchRow(
                label = stringResource(R.string.virtual_screen_auto_stop_after_run),
                description = stringResource(R.string.virtual_screen_auto_stop_after_run_desc),
                checked = settings.autoStopAfterRun,
            ) { save(settings.copy(autoStopAfterRun = it)) }

            Spacer(Modifier.height(SectionGap))
            SwitchRow(
                label = stringResource(R.string.virtual_screen_show_system_decorations),
                description = stringResource(R.string.virtual_screen_show_system_decorations_desc),
                checked = settings.showSystemDecorations,
            ) { save(settings.copy(showSystemDecorations = it)) }
        }

        Spacer(Modifier.height(SectionGap))
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
            style = MaterialTheme.typography.bodySmall,
            color = if (state is VirtualScreenState.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
