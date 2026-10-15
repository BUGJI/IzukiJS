package com.benton.izukijs.ui.settings

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.BuildConfig
import com.benton.izukijs.R
import com.benton.izukijs.controller.ControllerSettings
import com.benton.izukijs.data.AppearanceSettings
import com.benton.izukijs.data.EditorSettings
import com.benton.izukijs.data.ThemeMode
import com.benton.izukijs.i18n.AppLanguage
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.CapabilityGroup
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.ocr.OcrConfig
import com.benton.izukijs.ocr.OcrMode
import com.benton.izukijs.ocr.OcrProvider
import com.benton.izukijs.runtime.LogLevel
import com.benton.izukijs.service.CaptureSettings
import com.benton.izukijs.update.UpdateMethod
import com.benton.izukijs.update.UpdateSettings
import com.benton.izukijs.update.UpdateState
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.LabeledField
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.PageColumn
import com.benton.izukijs.ui.common.SecretField
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SwitchRow
import com.benton.izukijs.ui.common.findActivity
import com.benton.izukijs.ui.common.formatFileSize
import com.benton.izukijs.ui.common.localizedName
import com.benton.izukijs.ui.common.stableTopAppBarColors
import com.benton.izukijs.ui.rememberAppContainer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置分组。key 用于二级页面路由参数。 */
enum class SettingsCategory(val key: String, @StringRes val titleRes: Int) {
    GENERAL("general", R.string.settings_general),
    CONTROL("control", R.string.settings_control),
    STORAGE("storage", R.string.settings_storage),
    BACKUP("backup", R.string.settings_backup),
    ABOUT("about", R.string.settings_about),
    ;

    companion object {
        fun fromKey(key: String): SettingsCategory? = entries.firstOrNull { it.key == key }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenCategory: (SettingsCategory) -> Unit = {},
    onOpenAi: () -> Unit = {},
    onOpenMcp: () -> Unit = {},
) {
    val container = rememberAppContainer()
    val controllerSettings by container.controllerSettingsRepository.settings.collectAsStateWithLifecycle()
    val screenCaptureActive by container.screenCapture.active.collectAsStateWithLifecycle()
    val ocrConfig by container.ocrConfigRepository.config.collectAsStateWithLifecycle()
    val logSettings by container.logSettingsRepository.settings.collectAsStateWithLifecycle()
    val aiConfig by container.aiConfigRepository.config.collectAsStateWithLifecycle()
    val mcpStatus by container.mcpServerController.status.collectAsStateWithLifecycle()
    val language by container.languageRepository.language.collectAsStateWithLifecycle()
    val appearance by container.appearanceRepository.settings.collectAsStateWithLifecycle()
    val updateState by container.updateManager.state.collectAsStateWithLifecycle()

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                scrollBehavior = scrollBehavior,
                colors = stableTopAppBarColors(),
            )
        },
    ) { padding ->
        PageColumn(modifier = Modifier.padding(padding).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            CategoryGroup {
                CategoryRow(
                    title = stringResource(SettingsCategory.GENERAL.titleRes),
                    summary = stringResource(
                        R.string.settings_general_summary,
                        language.localizedName(),
                        appearance.themeMode.localizedName(),
                    ),
                ) { onOpenCategory(SettingsCategory.GENERAL) }
            }
            Spacer(Modifier.height(16.dp))
            CategoryGroup(stringResource(R.string.settings_group_control)) {
                CategoryRow(
                    title = stringResource(SettingsCategory.CONTROL.titleRes),
                    summary = stringResource(
                        R.string.settings_control_summary,
                        controllerSettings.readMode?.localizedName() ?: stringResource(R.string.common_auto),
                        controllerSettings.operateMode?.localizedName() ?: stringResource(R.string.common_auto),
                        stringResource(if (screenCaptureActive) R.string.common_on else R.string.common_off),
                        stringResource(if (ocrConfig.mode == OcrMode.LOCAL) R.string.settings_local else R.string.settings_online),
                    ),
                ) { onOpenCategory(SettingsCategory.CONTROL) }
                CategoryRow(
                    title = stringResource(R.string.settings_ai_agent),
                    summary = stringResource(
                        if (aiConfig.isConfigured) R.string.settings_ai_configured else R.string.settings_ai_not_configured,
                    ),
                ) { onOpenAi() }
                CategoryRow(
                    title = stringResource(R.string.settings_mcp_server),
                    summary = if (mcpStatus.running) {
                        stringResource(
                            R.string.settings_mcp_summary_running,
                            mcpStatus.addresses.firstOrNull().orEmpty(),
                        )
                    } else {
                        stringResource(R.string.settings_mcp_summary_stopped)
                    },
                ) { onOpenMcp() }
            }
            Spacer(Modifier.height(16.dp))
            CategoryGroup(stringResource(R.string.settings_group_data)) {
                CategoryRow(
                    title = stringResource(SettingsCategory.STORAGE.titleRes),
                    summary = stringResource(
                        R.string.settings_storage_summary,
                        logSettings.maxSizeMb,
                        logSettings.retentionDays,
                    ),
                ) { onOpenCategory(SettingsCategory.STORAGE) }
                CategoryRow(
                    title = stringResource(SettingsCategory.BACKUP.titleRes),
                    summary = stringResource(R.string.settings_backup_summary),
                ) { onOpenCategory(SettingsCategory.BACKUP) }
            }
            Spacer(Modifier.height(16.dp))
            CategoryGroup {
                CategoryRow(
                    title = stringResource(SettingsCategory.ABOUT.titleRes),
                    summary = when (val state = updateState) {
                        is UpdateState.Available ->
                            stringResource(R.string.update_available, state.info.versionName)

                        else -> stringResource(R.string.settings_version_short, BuildConfig.VERSION_NAME)
                    },
                ) { onOpenCategory(SettingsCategory.ABOUT) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun GeneralSettings() {
    val container = rememberAppContainer()
    val appearance by container.appearanceRepository.settings.collectAsStateWithLifecycle()
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun update(new: AppearanceSettings) = container.appearanceRepository.save(new)

    SectionCard(stringResource(R.string.settings_appearance)) {
        Text(
            stringResource(R.string.settings_appearance_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ChipFlow {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = appearance.themeMode == mode,
                    onClick = { update(appearance.copy(themeMode = mode)) },
                    label = { Text(mode.localizedName()) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_dynamic_color))
                Text(
                    stringResource(
                        if (dynamicColorSupported) {
                            R.string.settings_dynamic_color_desc
                        } else {
                            R.string.settings_dynamic_color_unsupported
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = dynamicColorSupported && appearance.dynamicColor,
                enabled = dynamicColorSupported,
                onCheckedChange = { update(appearance.copy(dynamicColor = it)) },
            )
        }
        Spacer(Modifier.height(12.dp))
        SwitchRow(
            label = stringResource(R.string.settings_amoled_title),
            description = stringResource(R.string.settings_amoled_desc),
            checked = appearance.amoledBackground,
            onChange = { update(appearance.copy(amoledBackground = it)) },
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.settings_custom_color), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(
                if (!appearance.dynamicColor) {
                    R.string.settings_custom_color_desc
                } else {
                    R.string.settings_custom_color_disabled_by_dynamic
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        val customColorEnabled = !appearance.dynamicColor
        ColorSwatchRow(
            selectedArgb = appearance.seedArgb,
            enabled = customColorEnabled,
            onSelect = { update(appearance.copy(seedArgb = it)) },
        )
        Spacer(Modifier.height(12.dp))
        HexColorField(
            value = appearance.seedArgb,
            enabled = customColorEnabled,
            onChange = { update(appearance.copy(seedArgb = it)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        ResetSeedColorButton(
            enabled = customColorEnabled && appearance.seedArgb != null,
            onClick = { update(appearance.copy(seedArgb = null)) },
        )
    }

    Spacer(Modifier.height(12.dp))
    LanguageSettings()

    Spacer(Modifier.height(12.dp))
    EditorSettingsSection()
}

@Composable
private fun LanguageSettings() {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val language by container.languageRepository.language.collectAsStateWithLifecycle()

    SectionCard(stringResource(R.string.settings_language)) {
        Text(
            stringResource(R.string.settings_language_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ChipFlow {
            AppLanguage.entries.forEach { option ->
                FilterChip(
                    selected = language == option,
                    onClick = {
                        if (language != option) {
                            container.languageRepository.save(option)
                            context.findActivity()?.recreate()
                        }
                    },
                    label = { Text(option.localizedName()) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDetailScreen(categoryKey: String, onBack: () -> Unit = {}) {
    val category = SettingsCategory.fromKey(categoryKey)
    SettingsDetailScaffold(
        title = category?.let { stringResource(it.titleRes) } ?: stringResource(R.string.settings_title),
        onBack = onBack,
    ) {
        when (category) {
            SettingsCategory.GENERAL -> GeneralSettings()
            SettingsCategory.CONTROL -> ControlVisionSettings()
            SettingsCategory.STORAGE -> StorageSettings()
            SettingsCategory.BACKUP -> BackupSettingsScreen()
            SettingsCategory.ABOUT -> AboutSettings()
            null -> Text(
                stringResource(R.string.settings_unknown_category),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ControlVisionSettings() {
    val container = rememberAppContainer()

    val controllerSettings by container.controllerSettingsRepository.settings.collectAsStateWithLifecycle()
    val readyModes by container.controllerManager.readyModes.collectAsStateWithLifecycle()
    val ocrConfig by container.ocrConfigRepository.config.collectAsStateWithLifecycle()
    val captureSettings by container.captureSettingsRepository.settings.collectAsStateWithLifecycle()

    fun saveController(new: ControllerSettings) = container.controllerSettingsRepository.save(new)
    fun updateOcr(new: OcrConfig) = container.ocrConfigRepository.save(new)
    fun updateCapture(new: CaptureSettings) = container.captureSettingsRepository.save(new)

    val capabilitiesByMode = container.controllerManager.capabilitiesByMode()
    val readModes = ControlMode.entries.filter { mode ->
        mode in readyModes && capabilitiesByMode[mode].orEmpty().any { it.group == CapabilityGroup.READ }
    }
    val operateModes = ControlMode.entries.filter { mode ->
        mode in readyModes && capabilitiesByMode[mode].orEmpty().any { it.group == CapabilityGroup.OPERATE }
    }

    PriorityCard(
        title = stringResource(R.string.ctrl_read_priority_title),
        description = stringResource(R.string.ctrl_read_priority_desc),
        effective = container.controllerManager.controllerFor(Capability.SCREENSHOT)?.mode?.localizedName(),
        availableModes = readModes,
        selectedMode = controllerSettings.readMode,
        onSelect = { saveController(controllerSettings.copy(readMode = it)) },
    )

    Spacer(Modifier.height(12.dp))
    PriorityCard(
        title = stringResource(R.string.ctrl_operate_priority_title),
        description = stringResource(R.string.ctrl_operate_priority_desc),
        effective = container.controllerManager.controllerFor(Capability.GESTURE)?.mode?.localizedName(),
        availableModes = operateModes,
        selectedMode = controllerSettings.operateMode,
        onSelect = { saveController(controllerSettings.copy(operateMode = it)) },
    )

    val choiceCapabilities = Capability.entries.filter { capability ->
        ControlMode.entries.count { mode ->
            mode in readyModes && capability in (capabilitiesByMode[mode] ?: emptySet())
        } >= 2
    }

    if (choiceCapabilities.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        SectionCard(stringResource(R.string.ctrl_cap_priority_title)) {
            Text(
                stringResource(R.string.ctrl_cap_priority_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CapabilityPreferenceBlock(
                title = stringResource(R.string.perm_group_read),
                capabilities = choiceCapabilities.filter { it.group == CapabilityGroup.READ },
                readyModes = readyModes,
                capabilitiesByMode = capabilitiesByMode,
                preferences = controllerSettings.capabilityPreferences,
            ) { capability, mode ->
                saveController(
                    controllerSettings.copy(
                        capabilityPreferences = withCapabilityPreference(
                            controllerSettings.capabilityPreferences,
                            capability,
                            mode,
                        ),
                    ),
                )
            }
            CapabilityPreferenceBlock(
                title = stringResource(R.string.perm_group_operate),
                capabilities = choiceCapabilities.filter { it.group == CapabilityGroup.OPERATE },
                readyModes = readyModes,
                capabilitiesByMode = capabilitiesByMode,
                preferences = controllerSettings.capabilityPreferences,
            ) { capability, mode ->
                saveController(
                    controllerSettings.copy(
                        capabilityPreferences = withCapabilityPreference(
                            controllerSettings.capabilityPreferences,
                            capability,
                            mode,
                        ),
                    ),
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    SectionCard(stringResource(R.string.ctrl_backends_title)) {
        Text(
            stringResource(R.string.ctrl_backends_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ControlMode.entries.forEach { mode ->
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(mode.localizedName())
                    Text(
                        stringResource(if (mode in readyModes) R.string.common_ready else R.string.common_not_ready),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = mode !in controllerSettings.disabledModes,
                    onCheckedChange = { enabled ->
                        val disabled = if (enabled) {
                            controllerSettings.disabledModes - mode
                        } else {
                            controllerSettings.disabledModes + mode
                        }
                        saveController(controllerSettings.copy(disabledModes = disabled))
                    },
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    SectionCard(stringResource(R.string.capture_compress_title)) {
        Text(
            stringResource(R.string.capture_compress_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.capture_scale), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        ChipFlow {
            listOf(100, 75, 50, 25).forEach { percent ->
                FilterChip(
                    selected = captureSettings.scalePercent == percent,
                    onClick = { updateCapture(captureSettings.copy(scalePercent = percent)) },
                    label = { Text("$percent%") },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.capture_jpeg_quality), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        ChipFlow {
            listOf(60, 75, 90, 100).forEach { quality ->
                FilterChip(
                    selected = captureSettings.jpegQuality == quality,
                    onClick = { updateCapture(captureSettings.copy(jpegQuality = quality)) },
                    label = { Text("$quality") },
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    SectionCard(stringResource(R.string.ocr_section_title)) {
        Text(
            stringResource(R.string.ocr_section_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ChipFlow {
            FilterChip(
                selected = ocrConfig.mode == OcrMode.LOCAL,
                onClick = { updateOcr(ocrConfig.copy(mode = OcrMode.LOCAL)) },
                label = { Text(stringResource(R.string.ocr_local_mlkit)) },
            )
            FilterChip(
                selected = ocrConfig.mode == OcrMode.ONLINE,
                onClick = { updateOcr(ocrConfig.copy(mode = OcrMode.ONLINE)) },
                label = { Text(stringResource(R.string.ocr_online)) },
            )
        }

        if (ocrConfig.mode == OcrMode.ONLINE) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.ocr_provider_label), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            ChipFlow {
                OcrProvider.entries.forEach { provider ->
                    FilterChip(
                        selected = ocrConfig.provider == provider,
                        onClick = { updateOcr(ocrConfig.copy(provider = provider)) },
                        label = { Text(provider.localizedName()) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            when (ocrConfig.provider) {
                OcrProvider.BAIDU -> {
                    SecretField(stringResource(R.string.ocr_api_key), ocrConfig.apiKey, commitOnBlur = true) {
                        updateOcr(ocrConfig.copy(apiKey = it))
                    }
                    SecretField(stringResource(R.string.ocr_secret_key), ocrConfig.secretKey, commitOnBlur = true) {
                        updateOcr(ocrConfig.copy(secretKey = it))
                    }
                }

                OcrProvider.GOOGLE_VISION -> {
                    SecretField(stringResource(R.string.ocr_api_key), ocrConfig.apiKey, commitOnBlur = true) {
                        updateOcr(ocrConfig.copy(apiKey = it))
                    }
                }

                OcrProvider.CUSTOM -> {
                    LabeledField(stringResource(R.string.ocr_endpoint), ocrConfig.endpoint) {
                        updateOcr(ocrConfig.copy(endpoint = it))
                    }
                    LabeledField(stringResource(R.string.ocr_header_name), ocrConfig.headerName) {
                        updateOcr(ocrConfig.copy(headerName = it))
                    }
                    SecretField(stringResource(R.string.ocr_header_value), ocrConfig.headerValue, commitOnBlur = true) {
                        updateOcr(ocrConfig.copy(headerValue = it))
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageSettings() {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logSettings by container.logSettingsRepository.settings.collectAsStateWithLifecycle()
    var logFileSize by remember { mutableStateOf(0L) }
    var confirmClearLog by remember { mutableStateOf(false) }

    fun refreshLogSize() {
        scope.launch {
            logFileSize = withContext(Dispatchers.IO) { container.logFileStore.sizeBytes() }
        }
    }

    LaunchedEffect(Unit) { refreshLogSize() }

    SectionCard(stringResource(R.string.storage_log_title)) {
        SwitchRow(
            label = stringResource(R.string.storage_auto_clean),
            checked = logSettings.autoClean,
        ) {
            container.logSettingsRepository.save(logSettings.copy(autoClean = it))
            container.logFileStore.trim()
            refreshLogSize()
        }
        Spacer(Modifier.height(12.dp))

        Text(stringResource(R.string.storage_log_level), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        ChipFlow {
            listOf(
                LogLevel.DEBUG to stringResource(R.string.log_level_all),
                LogLevel.INFO to stringResource(R.string.log_level_info),
                LogLevel.WARN to stringResource(R.string.log_level_warn),
                LogLevel.ERROR to stringResource(R.string.log_level_error),
            ).forEach { (level, label) ->
                FilterChip(
                    selected = logSettings.minLevel == level,
                    onClick = {
                        container.logSettingsRepository.save(logSettings.copy(minLevel = level))
                    },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        Text(stringResource(R.string.storage_max_size), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        ChipFlow {
            listOf(1, 5, 10, 20).forEach { size ->
                FilterChip(
                    selected = logSettings.maxSizeMb == size,
                    onClick = {
                        container.logSettingsRepository.save(logSettings.copy(maxSizeMb = size))
                        container.logFileStore.trim()
                        refreshLogSize()
                    },
                    label = { Text("$size MB") },
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        Text(stringResource(R.string.storage_retention_days), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        ChipFlow {
            listOf(1, 3, 7, 30).forEach { days ->
                FilterChip(
                    selected = logSettings.retentionDays == days,
                    onClick = {
                        container.logSettingsRepository.save(logSettings.copy(retentionDays = days))
                        container.logFileStore.trim()
                        refreshLogSize()
                    },
                    label = { Text(stringResource(R.string.storage_days, days)) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.storage_usage, formatFileSize(logFileSize)),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { confirmClearLog = true }) {
                Text(stringResource(R.string.storage_clear_now))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    val file = File(
                        File(context.cacheDir, "exports"),
                        "izukijs_log_${System.currentTimeMillis()}.log",
                    )
                    val ok = withContext(Dispatchers.IO) { container.logFileStore.exportTo(file) }
                    if (ok) shareFile(context, file)
                }
            }) { Text(stringResource(R.string.storage_export_log)) }
        }
    }

    if (confirmClearLog) {
        AlertDialog(
            onDismissRequest = { confirmClearLog = false },
            title = { Text(stringResource(R.string.storage_clear_now)) },
            text = { Text(stringResource(R.string.storage_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    container.logBus.clear()
                    container.logFileStore.clear()
                    refreshLogSize()
                    confirmClearLog = false
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearLog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

private fun shareFile(context: android.content.Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.storage_export_log_chooser))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun AboutSettings() {
    SectionCard(stringResource(R.string.settings_about)) {
        Text(stringResource(R.string.about_name), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.about_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(12.dp))
    UpdateSettingsSection()
}

@Composable
private fun UpdateSettingsSection() {
    val container = rememberAppContainer()
    val settings by container.updateSettingsRepository.settings.collectAsStateWithLifecycle()
    val state by container.updateManager.state.collectAsStateWithLifecycle()

    fun update(new: UpdateSettings) = container.updateSettingsRepository.save(new)

    SectionCard(stringResource(R.string.update_section_title)) {
        Text(
            stringResource(R.string.update_current_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        UpdateStatusLine(state)
        Spacer(Modifier.height(12.dp))

        val busy = state is UpdateState.Checking || state is UpdateState.Downloading
        val info = (state as? UpdateState.Available)?.info
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { container.updateManager.checkManually() },
                enabled = !busy,
            ) { Text(stringResource(R.string.update_check_button)) }

            if (info != null) {
                val inApp = settings.method == UpdateMethod.IN_APP && !info.apkUrl.isNullOrBlank()
                OutlinedButton(
                    onClick = {
                        if (inApp) {
                            container.updateManager.downloadAndInstall()
                        } else {
                            container.updateManager.openOnGitHub()
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (inApp) R.string.update_download else R.string.update_open_github,
                        ),
                    )
                }
            }

            if (state is UpdateState.Downloaded) {
                OutlinedButton(onClick = { container.updateManager.installDownloaded() }) {
                    Text(stringResource(R.string.update_install))
                }
            }
        }

        if (info != null && settings.method == UpdateMethod.IN_APP && info.apkUrl.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.update_no_asset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        SwitchRow(
            label = stringResource(R.string.update_auto_check),
            description = stringResource(R.string.update_auto_check_desc),
            checked = settings.autoCheck,
        ) { update(settings.copy(autoCheck = it)) }

        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.update_method), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        ChipFlow {
            UpdateMethod.entries.forEach { method ->
                FilterChip(
                    selected = settings.method == method,
                    onClick = { update(settings.copy(method = method)) },
                    label = {
                        Text(
                            stringResource(
                                when (method) {
                                    UpdateMethod.IN_APP -> R.string.update_method_in_app
                                    UpdateMethod.GITHUB -> R.string.update_method_github
                                },
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun UpdateStatusLine(state: UpdateState) {
    when (state) {
        UpdateState.Idle -> Unit

        UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.update_checking),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        UpdateState.UpToDate -> Text(
            stringResource(R.string.update_up_to_date),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is UpdateState.Available -> Text(
            stringResource(R.string.update_available, state.info.versionName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        is UpdateState.Downloading -> Column {
            Text(
                if (state.progress < 0) {
                    stringResource(R.string.update_downloading_unknown)
                } else {
                    stringResource(R.string.update_downloading, state.progress)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            if (state.progress < 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        is UpdateState.Downloaded -> Text(
            stringResource(R.string.update_downloaded),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is UpdateState.CheckFailed -> Text(
            stringResource(R.string.update_check_failed, state.message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        is UpdateState.DownloadFailed -> Text(
            stringResource(R.string.update_download_failed, state.message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun EditorSettingsSection() {
    val container = rememberAppContainer()
    val editorSettings by container.editorSettingsRepository.settings.collectAsStateWithLifecycle()

    fun update(new: EditorSettings) = container.editorSettingsRepository.save(new)

    SectionCard(stringResource(R.string.settings_editor)) {
        Text(stringResource(R.string.editor_font_size), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        ChipFlow {
            listOf(12, 13, 14, 16, 18, 20).forEach { size ->
                FilterChip(
                    selected = editorSettings.fontSizeSp == size,
                    onClick = { update(editorSettings.copy(fontSizeSp = size)) },
                    label = { Text("$size") },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.editor_auto_save))
                Text(
                    stringResource(R.string.editor_auto_save_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = editorSettings.autoSave,
                onCheckedChange = { update(editorSettings.copy(autoSave = it)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupSettingsScreen() {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarController.current
    var confirmReset by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            val msg = if (text.isNullOrBlank()) {
                resources.getString(R.string.backup_read_failed)
            } else {
                runCatching { container.configBackupManager.importJson(text) }
                    .fold(
                        onSuccess = { resources.getString(R.string.backup_import_success) },
                        onFailure = {
                            resources.getString(R.string.backup_import_failed, it.message.orEmpty())
                        },
                    )
            }
            snackbar.show(msg)
        }
    }

    SectionCard(stringResource(R.string.settings_backup)) {
        Text(
            stringResource(R.string.backup_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    val file = File(
                        File(context.cacheDir, "exports"),
                        "izukijs_config_${System.currentTimeMillis()}.json",
                    )
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            file.parentFile?.mkdirs()
                            file.writeText(container.configBackupManager.exportJson())
                            true
                        }.getOrDefault(false)
                    }
                    if (ok) {
                        shareFile(context, file)
                    } else {
                        snackbar.show(resources.getString(R.string.backup_export_failed))
                    }
                }
            }) { Text(stringResource(R.string.backup_export)) }
            OutlinedButton(onClick = {
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }) { Text(stringResource(R.string.backup_import)) }
        }
    }

    Spacer(Modifier.height(12.dp))
    SectionCard(stringResource(R.string.backup_reset_title)) {
        Text(
            stringResource(R.string.backup_reset_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.backup_reset_button)) }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.backup_reset_button)) },
            text = { Text(stringResource(R.string.backup_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { container.configBackupManager.resetAll() }
                        .onSuccess { scope.launch { snackbar.show(resources.getString(R.string.backup_reset_done)) } }
                        .onFailure { scope.launch { snackbar.show(resources.getString(R.string.backup_reset_failed)) } }
                    confirmReset = false
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * 分组容器：每一项各自一张卡片，靠间距区分，不使用卡片内部分隔线。
 * 参照 LSPosed Manager 的设置列表，避免细线在深色卡片上形成明显「隔断」。
 */
@Composable
private fun CategoryGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        content()
    }
}

@Composable
private fun CategoryRow(title: String, summary: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDetailScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                scrollBehavior = scrollBehavior,
                colors = stableTopAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        PageColumn(modifier = Modifier.padding(padding).padding(horizontal = 16.dp)) {
            content()
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 全局优先级卡片：以 ChipFlow 呈现「自动 + 各可用后端」，并在下方标注当前实际生效的后端。
 * 仅列出该分组下已就绪的后端，避免选中用不了的后端。
 */
@Composable
private fun PriorityCard(
    title: String,
    description: String,
    effective: String?,
    availableModes: List<ControlMode>,
    selectedMode: ControlMode?,
    onSelect: (ControlMode?) -> Unit,
) {
    SectionCard(title) {
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (availableModes.isEmpty()) {
            Text(
                stringResource(R.string.ctrl_priority_no_backend),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        ChipFlow {
            FilterChip(
                selected = selectedMode == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.common_auto)) },
            )
            availableModes.forEach { mode ->
                FilterChip(
                    selected = selectedMode == mode,
                    onClick = { onSelect(mode) },
                    label = { Text(mode.localizedName()) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                R.string.ctrl_effective,
                effective ?: stringResource(R.string.common_none),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 单项能力的优先模式选择块。无候选能力时不渲染，便于「读取 / 操作」两段按需显隐。
 */
@Composable
private fun CapabilityPreferenceBlock(
    title: String,
    capabilities: List<Capability>,
    readyModes: Set<ControlMode>,
    capabilitiesByMode: Map<ControlMode, Set<Capability>>,
    preferences: Map<Capability, ControlMode>,
    onSelect: (Capability, ControlMode?) -> Unit,
) {
    if (capabilities.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    capabilities.forEach { capability ->
        Spacer(Modifier.height(8.dp))
        Text(capability.localizedName(), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        ChipFlow {
            FilterChip(
                selected = preferences[capability] == null,
                onClick = { onSelect(capability, null) },
                label = { Text(stringResource(R.string.ctrl_follow_global)) },
            )
            ControlMode.entries
                .filter { mode -> mode in readyModes && capability in (capabilitiesByMode[mode] ?: emptySet()) }
                .forEach { mode ->
                    FilterChip(
                        selected = preferences[capability] == mode,
                        onClick = { onSelect(capability, mode) },
                        label = { Text(mode.localizedName()) },
                    )
                }
        }
    }
}

/** 为某项能力设置 / 清除优先后端：mode 为空表示跟随全局。 */
private fun withCapabilityPreference(
    preferences: Map<Capability, ControlMode>,
    capability: Capability,
    mode: ControlMode?,
): Map<Capability, ControlMode> =
    if (mode == null) preferences - capability else preferences + (capability to mode)
