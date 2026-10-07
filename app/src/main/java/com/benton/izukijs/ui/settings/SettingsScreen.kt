package com.benton.izukijs.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.BuildConfig
import com.benton.izukijs.controller.ControllerSettings
import com.benton.izukijs.data.EditorSettings
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.ocr.OcrConfig
import com.benton.izukijs.ocr.OcrMode
import com.benton.izukijs.ocr.OcrProvider
import com.benton.izukijs.runtime.LogLevel
import com.benton.izukijs.service.CaptureSettings
import com.benton.izukijs.ui.common.LabeledField
import com.benton.izukijs.ui.common.PageColumn
import com.benton.izukijs.ui.common.SecretField
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SwitchRow
import com.benton.izukijs.ui.common.formatFileSize
import com.benton.izukijs.ui.rememberAppContainer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置分组。key 用于二级页面路由参数。 */
enum class SettingsCategory(val key: String, val title: String) {
    CONTROL("control", "控制与视觉"),
    EDITOR("editor", "编辑器"),
    STORAGE("storage", "存储与日志"),
    BACKUP("backup", "备份与恢复"),
    ABOUT("about", "关于"),
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
            TopAppBar(title = { Text("设置") })
        },
    ) { padding ->
        PageColumn(modifier = Modifier.padding(padding).padding(16.dp)) {
            CategoryCard {
                CategoryRow(
                    title = SettingsCategory.CONTROL.title,
                    summary = "优先 ${controllerSettings.preferredMode?.displayName ?: "自动"} · 录屏${
                        if (screenCaptureActive) "开" else "关"
                    } · OCR ${if (ocrConfig.mode == OcrMode.LOCAL) "本地" else "在线"}",
                ) { onOpenCategory(SettingsCategory.CONTROL) }
                CategoryDivider()
                CategoryRow(
                    title = SettingsCategory.EDITOR.title,
                    summary = "字号 ${editorSettings.fontSizeSp} · 自动保存${
                        if (editorSettings.autoSave) "开" else "关"
                    }",
                ) { onOpenCategory(SettingsCategory.EDITOR) }
                CategoryDivider()
                CategoryRow(
                    title = "AI Agent",
                    summary = if (aiConfig.isConfigured) "已配置" else "未配置",
                ) { onOpenAi() }
                CategoryDivider()
                CategoryRow(
                    title = SettingsCategory.STORAGE.title,
                    summary = "${logSettings.maxSizeMb}MB · 保留 ${logSettings.retentionDays} 天",
                ) { onOpenCategory(SettingsCategory.STORAGE) }
                CategoryDivider()
                CategoryRow(
                    title = SettingsCategory.BACKUP.title,
                    summary = "导出 / 导入 / 重置",
                ) { onOpenCategory(SettingsCategory.BACKUP) }
                CategoryDivider()
                CategoryRow(
                    title = SettingsCategory.ABOUT.title,
                    summary = "v${BuildConfig.VERSION_NAME}",
                ) { onOpenCategory(SettingsCategory.ABOUT) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDetailScreen(categoryKey: String, onBack: () -> Unit = {}) {
    val category = SettingsCategory.fromKey(categoryKey)
    SettingsDetailScaffold(title = category?.title ?: "设置", onBack = onBack) {
        when (category) {
            SettingsCategory.CONTROL -> ControlVisionSettings()
            SettingsCategory.EDITOR -> EditorSettingsScreen()
            SettingsCategory.STORAGE -> StorageSettings()
            SettingsCategory.BACKUP -> BackupSettingsScreen()
            SettingsCategory.ABOUT -> AboutSettings()
            null -> Text(
                "未知的设置分组。",
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

    SectionCard("控制模式优先级") {
        Text(
            "当某项能力有多个后端可用时，优先使用选中的模式。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        PreferenceRow(
            label = "自动",
            selected = controllerSettings.preferredMode == null,
            onSelect = { saveController(controllerSettings.copy(preferredMode = null)) },
        )
        ControlMode.entries.forEach { mode ->
            PreferenceRow(
                label = mode.displayName,
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
        SectionCard("按能力指定优先模式") {
            Text(
                "可为不同能力分别指定后端；未指定的能力跟随上方全局设置。仅列出至少有两个可用后端的项。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            choiceCapabilities.forEach { capability ->
                Spacer(Modifier.height(8.dp))
                Text(capability.displayName, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = controllerSettings.capabilityPreferences[capability] == null,
                        onClick = {
                            saveController(
                                controllerSettings.copy(
                                    capabilityPreferences = controllerSettings.capabilityPreferences - capability,
                                ),
                            )
                        },
                        label = { Text("跟随全局") },
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
                                label = { Text(mode.displayName) },
                            )
                        }
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    SectionCard("控制后端") {
        Text(
            "关闭后该后端不参与能力协商；已建立的连接（如无障碍服务、Shizuku）本身不受影响。",
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
                    Text(mode.displayName)
                    Text(
                        if (mode in readyModes) "已就绪" else "未就绪",
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
    SectionCard("截图压缩") {
        Text(
            "影响「保存截图」的文件大小与在线 OCR 的上传流量；图像匹配、找色与本地识别仍使用原始分辨率，坐标不受影响。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text("缩放比例", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(100, 75, 50, 25).forEach { percent ->
                FilterChip(
                    selected = captureSettings.scalePercent == percent,
                    onClick = { updateCapture(captureSettings.copy(scalePercent = percent)) },
                    label = { Text("$percent%") },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("JPEG 质量", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
    SectionCard("OCR 识别") {
        Text(
            "低端设备可切换到在线识别以节省本地算力；在线失败会自动回退本地。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = ocrConfig.mode == OcrMode.LOCAL,
                onClick = { updateOcr(ocrConfig.copy(mode = OcrMode.LOCAL)) },
                label = { Text("本地 (MLKit)") },
            )
            FilterChip(
                selected = ocrConfig.mode == OcrMode.ONLINE,
                onClick = { updateOcr(ocrConfig.copy(mode = OcrMode.ONLINE)) },
                label = { Text("在线") },
            )
        }

        if (ocrConfig.mode == OcrMode.ONLINE) {
            Spacer(Modifier.height(12.dp))
            Text("服务商", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OcrProvider.entries.forEach { provider ->
                    FilterChip(
                        selected = ocrConfig.provider == provider,
                        onClick = { updateOcr(ocrConfig.copy(provider = provider)) },
                        label = { Text(provider.displayName) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            when (ocrConfig.provider) {
                OcrProvider.BAIDU -> {
                    SecretField("API Key", ocrConfig.apiKey) {
                        updateOcr(ocrConfig.copy(apiKey = it))
                    }
                    SecretField("Secret Key", ocrConfig.secretKey) {
                        updateOcr(ocrConfig.copy(secretKey = it))
                    }
                }

                OcrProvider.GOOGLE_VISION -> {
                    SecretField("API Key", ocrConfig.apiKey) {
                        updateOcr(ocrConfig.copy(apiKey = it))
                    }
                }

                OcrProvider.CUSTOM -> {
                    LabeledField("请求地址 (POST)", ocrConfig.endpoint) {
                        updateOcr(ocrConfig.copy(endpoint = it))
                    }
                    LabeledField("鉴权 Header 名", ocrConfig.headerName) {
                        updateOcr(ocrConfig.copy(headerName = it))
                    }
                    SecretField("鉴权 Header 值", ocrConfig.headerValue) {
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

    SectionCard("日志存储") {
        SwitchRow(
            label = "自动清理",
            checked = logSettings.autoClean,
        ) {
            container.logSettingsRepository.save(logSettings.copy(autoClean = it))
            container.logFileStore.trim()
            refreshLogSize()
        }
        Spacer(Modifier.height(12.dp))

        Text("日志级别", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                LogLevel.DEBUG to "全部",
                LogLevel.INFO to "信息",
                LogLevel.WARN to "警告",
                LogLevel.ERROR to "错误",
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

        Text("最大存储", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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

        Text("保留天数", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(1, 3, 7, 30).forEach { days ->
                FilterChip(
                    selected = logSettings.retentionDays == days,
                    onClick = {
                        container.logSettingsRepository.save(logSettings.copy(retentionDays = days))
                        container.logFileStore.trim()
                        refreshLogSize()
                    },
                    label = { Text("$days 天") },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "当前占用 ${formatFileSize(logFileSize)}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = {
                container.logBus.clear()
                container.logFileStore.clear()
                refreshLogSize()
            }) { Text("立即清空") }
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
            }) { Text("导出") }
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
            Intent.createChooser(send, "导出日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun AboutSettings() {
    SectionCard("关于") {
        Text("IzukiJS", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "基于 Kotlin / Jetpack Compose 的 Android 自动化脚本运行环境。",
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

    SectionCard("编辑器") {
        Text("字号", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
                Text("自动保存")
                Text(
                    "停止输入后自动写盘；关闭后返回 / 运行时仍会保存。",
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
                "读取文件失败"
            } else {
                runCatching { container.configBackupManager.importJson(text) }
                    .fold(onSuccess = { "导入成功" }, onFailure = { "导入失败：${it.message}" })
            }
        }
    }

    SectionCard("备份与恢复") {
        Text(
            "导出 / 导入包含 AI、OCR、控制、截图、日志与编辑器偏好。导出文件含明文密钥，请妥善保管；不含脚本文件。",
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
                    if (ok) shareFile(context, file) else message = "导出失败"
                }
            }) { Text("导出配置") }
            OutlinedButton(onClick = {
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }) { Text("导入配置") }
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }

    Spacer(Modifier.height(12.dp))
    SectionCard("重置") {
        Text(
            "将全部设置恢复为默认值，不影响脚本内容。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { confirmReset = true }) { Text("恢复默认设置") }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复默认设置") },
            text = { Text("确定要将全部设置恢复为默认值吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { container.configBackupManager.resetAll() }
                    message = "已恢复默认设置"
                    confirmReset = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("取消") }
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
