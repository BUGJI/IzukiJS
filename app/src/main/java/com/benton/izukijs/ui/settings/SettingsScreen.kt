package com.benton.izukijs.ui.settings

import android.content.Intent
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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.BuildConfig
import com.benton.izukijs.R
import com.benton.izukijs.controller.ControllerSettings
import com.benton.izukijs.data.EditorSettings
import com.benton.izukijs.i18n.AppLanguage
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.ocr.OcrConfig
import com.benton.izukijs.ocr.OcrMode
import com.benton.izukijs.ocr.OcrProvider
import com.benton.izukijs.runtime.LogLevel
import com.benton.izukijs.service.CaptureSettings
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.LabeledField
import com.benton.izukijs.ui.common.PageColumn
import com.benton.izukijs.ui.common.SecretField
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SwitchRow
import com.benton.izukijs.ui.common.findActivity
import com.benton.izukijs.ui.common.formatFileSize
import com.benton.izukijs.ui.common.localizedName
import com.benton.izukijs.ui.rememberAppContainer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置分组。key 用于二级页面路由参数。 */
enum class SettingsCategory(val key: String, @StringRes val titleRes: Int) {
    CONTROL("control", R.string.settings_control),
    EDITOR("editor", R.string.settings_editor),
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
) {
    val container = rememberAppContainer()
    val controllerSettings by container.controllerSettingsRepository.settings.collectAsStateWithLifecycle()
    val screenCaptureActive by container.screenCapture.active.collectAsStateWithLifecycle()
    val ocrConfig by container.ocrConfigRepository.config.collectAsStateWithLifecycle()
    val logSettings by container.logSettingsRepository.settings.collectAsStateWithLifecycle()
    val aiConfig by container.aiConfigRepository.config.collectAsStateWithLifecycle()
    val editorSettings by container.editorSettingsRepository.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings_title)) })
        },
    ) { padding ->
        PageColumn(modifier = Modifier.padding(padding).padding(16.dp)) {
            LanguageSettings()
            Spacer(Modifier.height(12.dp))
            CategoryCard {
                CategoryRow(
                    title = stringResource(SettingsCategory.CONTROL.titleRes),
                    summary = stringResource(
                        R.string.settings_control_summary,
                        controllerSettings.preferredMode?.localizedName() ?: stringResource(R.string.common_auto),
                        stringResource(if (screenCaptureActive) R.string.common_on else R.string.common_off),
                        stringResource(if (ocrConfig.mode == OcrMode.LOCAL) R.string.settings_local else R.string.settings_online),
                    ),
                ) { onOpenCategory(SettingsCategory.CONTROL) }
                CategoryDivider()
                CategoryRow(
                    title = stringResource(SettingsCategory.EDITOR.titleRes),
                    summary = stringResource(
                        R.string.settings_editor_summary,
                        editorSettings.fontSizeSp,
                        stringResource(if (editorSettings.autoSave) R.string.common_on else R.string.common_off),
                    ),
                ) { onOpenCategory(SettingsCategory.EDITOR) }
                CategoryDivider()
                CategoryRow(
                    title = stringResource(R.string.settings_ai_agent),
                    summary = stringResource(
                        if (aiConfig.isConfigured) R.string.settings_ai_configured else R.string.settings_ai_not_configured,
                    ),
                ) { onOpenAi() }
                CategoryDivider()
                CategoryRow(
                    title = stringResource(SettingsCategory.STORAGE.titleRes),
                    summary = stringResource(
                        R.string.settings_storage_summary,
                        logSettings.maxSizeMb,
                        logSettings.retentionDays,
                    ),
                ) { onOpenCategory(SettingsCategory.STORAGE) }
                CategoryDivider()
                CategoryRow(
                    title = stringResource(SettingsCategory.BACKUP.titleRes),
                    summary = stringResource(R.string.settings_backup_summary),
                ) { onOpenCategory(SettingsCategory.BACKUP) }
                CategoryDivider()
                CategoryRow(
                    title = stringResource(SettingsCategory.ABOUT.titleRes),
                    summary = stringResource(R.string.settings_version_short, BuildConfig.VERSION_NAME),
                ) { onOpenCategory(SettingsCategory.ABOUT) }
            }
        }
    }
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
            SettingsCategory.CONTROL -> ControlVisionSettings()
            SettingsCategory.EDITOR -> EditorSettingsScreen()
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

    SectionCard(stringResource(R.string.ctrl_priority_title)) {
        Text(
            stringResource(R.string.ctrl_priority_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        PreferenceRow(
            label = stringResource(R.string.common_auto),
            selected = controllerSettings.preferredMode == null,
            onSelect = { saveController(controllerSettings.copy(preferredMode = null)) },
        )
        ControlMode.entries.forEach { mode ->
            PreferenceRow(
                label = mode.localizedName(),
                selected = controllerSettings.preferredMode == mode,
                onSelect = { saveController(controllerSettings.copy(preferredMode = mode)) },
            )
        }
    }

    val capabilitiesByMode = container.controllerManager.capabilitiesByMode()
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
            choiceCapabilities.forEach { capability ->
                Spacer(Modifier.height(8.dp))
                Text(capability.localizedName(), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                ChipFlow {
                    FilterChip(
                        selected = controllerSettings.capabilityPreferences[capability] == null,
                        onClick = {
                            saveController(
                                controllerSettings.copy(
                                    capabilityPreferences = controllerSettings.capabilityPreferences - capability,
                                ),
                            )
                        },
                        label = { Text(stringResource(R.string.ctrl_follow_global)) },
                    )
                    ControlMode.entries
                        .filter { mode ->
                            mode in readyModes && capability in (capabilitiesByMode[mode] ?: emptySet())
                        }
                        .forEach { mode ->
                            FilterChip(
                                selected = controllerSettings.capabilityPreferences[capability] == mode,
                                onClick = {
                                    saveController(
                                        controllerSettings.copy(
                                            capabilityPreferences = controllerSettings.capabilityPreferences +
                                                (capability to mode),
                                        ),
                                    )
                                },
                                label = { Text(mode.localizedName()) },
                            )
                        }
                }
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
                    SecretField(stringResource(R.string.ocr_api_key), ocrConfig.apiKey) {
                        updateOcr(ocrConfig.copy(apiKey = it))
                    }
                    SecretField(stringResource(R.string.ocr_secret_key), ocrConfig.secretKey) {
                        updateOcr(ocrConfig.copy(secretKey = it))
                    }
                }

                OcrProvider.GOOGLE_VISION -> {
                    SecretField(stringResource(R.string.ocr_api_key), ocrConfig.apiKey) {
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
                    SecretField(stringResource(R.string.ocr_header_value), ocrConfig.headerValue) {
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
            OutlinedButton(onClick = {
                container.logBus.clear()
                container.logFileStore.clear()
                refreshLogSize()
            }) { Text(stringResource(R.string.storage_clear_now)) }
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
        Text(stringResource(R.string.about_name), style = MaterialTheme.typography.titleMedium)
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
}

@Composable
private fun EditorSettingsScreen() {
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
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
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
            message = if (text.isNullOrBlank()) {
                context.getString(R.string.backup_read_failed)
            } else {
                runCatching { container.configBackupManager.importJson(text) }
                    .fold(
                        onSuccess = { context.getString(R.string.backup_import_success) },
                        onFailure = {
                            context.getString(R.string.backup_import_failed, it.message.orEmpty())
                        },
                    )
            }
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
                    if (ok) shareFile(context, file) else message = context.getString(R.string.backup_export_failed)
                }
            }) { Text(stringResource(R.string.backup_export)) }
            OutlinedButton(onClick = {
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }) { Text(stringResource(R.string.backup_import)) }
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
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
                    message = context.getString(R.string.backup_reset_done)
                    confirmReset = false
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun CategoryCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

@Composable
private fun CategoryRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CategoryDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDetailScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        PageColumn(modifier = Modifier.padding(padding).padding(16.dp)) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PreferenceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
    }
}
