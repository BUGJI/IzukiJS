package com.benton.izukijs.ui.settings

import com.benton.izukijs.ui.common.SectionGap
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.BuildConfig
import com.benton.izukijs.R
import com.benton.izukijs.ocr.OcrMode
import com.benton.izukijs.virtualscreen.VirtualScreenState
import com.benton.izukijs.update.UpdateState
import com.benton.izukijs.ui.common.IzukiPageScaffold
import com.benton.izukijs.ui.common.PageColumn
import com.benton.izukijs.ui.common.localizedName
import com.benton.izukijs.ui.appViewModel

/** 设置分组。key 用于二级页面路由参数。 */
enum class SettingsCategory(
    val key: String,
    @StringRes val titleRes: Int,
    val icon: ImageVector,
) {
    GENERAL("general", R.string.settings_general, Icons.Filled.Settings),
    CONTROL("control", R.string.settings_control, Icons.Filled.Gamepad),
    VISION("vision", R.string.settings_vision, Icons.Filled.Visibility),
    RUN("run", R.string.settings_run, Icons.Filled.Code),
    NOTIFY("notify", R.string.settings_notify, Icons.Filled.Notifications),
    DISPLAY("display", R.string.settings_display, Icons.Filled.PictureInPicture),
    UNLOCK("unlock", R.string.settings_unlock, Icons.Filled.LockOpen),
    ENV("env", R.string.settings_env, Icons.Filled.Key),
    LOG("log", R.string.settings_storage, Icons.AutoMirrored.Filled.Article),
    RECORD("record", R.string.settings_record, Icons.Filled.FiberManualRecord),
    BACKUP("backup", R.string.settings_backup, Icons.Filled.Backup),
    DEVELOPER("developer", R.string.settings_developer, Icons.Filled.Build),
    ABOUT("about", R.string.settings_about, Icons.Filled.Info),
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
    onOpenSetup: () -> Unit = {},
) {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    IzukiPageScaffold(
        title = { Text(stringResource(R.string.settings_title)) },
    ) { padding ->
        PageColumn(modifier = Modifier.padding(padding).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(SectionGap))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.settings_search_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(SectionGap))
            if (query.isNotBlank()) {
                val needle = query.trim()
                val developerTitle = stringResource(R.string.settings_developer)
                val results = settingsSearchEntries(
                    onOpenCategory = onOpenCategory,
                    onOpenAi = onOpenAi,
                    onOpenMcp = onOpenMcp,
                    onOpenSetup = onOpenSetup,
                ).filter {
                    (ui.developerSettings.unlocked || it.title != developerTitle) &&
                        (it.title.contains(needle, ignoreCase = true) ||
                            it.keywords.contains(needle, ignoreCase = true))
                }
                if (results.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_search_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    CategoryGroup {
                        results.forEach { entry ->
                            CategoryRow(
                                icon = entry.icon,
                                title = entry.title,
                                summary = stringResource(R.string.settings_search_open),
                            ) { entry.open() }
                        }
                    }
                }
                Spacer(Modifier.height(SectionGap))
            } else {
            CategoryGroup {
                CategoryRow(
                    category = SettingsCategory.GENERAL,
                    summary = stringResource(
                        R.string.settings_general_summary,
                        ui.language.localizedName(),
                        ui.appearance.themeMode.localizedName(),
                    ),
                ) { onOpenCategory(SettingsCategory.GENERAL) }
            }
            Spacer(Modifier.height(SectionGap))
            CategoryGroup(stringResource(R.string.settings_group_automation)) {
                CategoryRow(
                    category = SettingsCategory.CONTROL,
                    summary = stringResource(
                        R.string.settings_control_summary,
                        ui.controllerSettings.readMode?.localizedName() ?: stringResource(R.string.common_auto),
                        ui.controllerSettings.operateMode?.localizedName() ?: stringResource(R.string.common_auto),
                    ),
                ) { onOpenCategory(SettingsCategory.CONTROL) }
                CategoryRow(
                    category = SettingsCategory.VISION,
                    summary = stringResource(
                        R.string.settings_vision_summary,
                        ui.captureSettings.scalePercent,
                        stringResource(
                            if (ui.ocrConfig.mode == OcrMode.LOCAL) {
                                R.string.settings_local
                            } else {
                                R.string.settings_online
                            },
                        ),
                    ),
                ) { onOpenCategory(SettingsCategory.VISION) }
                CategoryRow(
                    category = SettingsCategory.RUN,
                    summary = stringResource(
                        R.string.settings_run_summary,
                        if (ui.runtimeSettings.scriptTimeoutMinutes <= 0) {
                            stringResource(R.string.perm_timeout_unlimited)
                        } else {
                            stringResource(
                                R.string.perm_timeout_minutes,
                                ui.runtimeSettings.scriptTimeoutMinutes,
                            )
                        },
                        stringResource(if (ui.runtimeSettings.keepAwake) R.string.common_on else R.string.common_off),
                    ),
                ) { onOpenCategory(SettingsCategory.RUN) }
                CategoryRow(
                    category = SettingsCategory.NOTIFY,
                    summary = pluralStringResource(
                        R.plurals.settings_notify_summary,
                        ui.notificationSettings.channels.size,
                        ui.notificationSettings.channels.size,
                        stringResource(
                            if (ui.notificationSettings.enabled) R.string.common_on else R.string.common_off,
                        ),
                    ),
                ) { onOpenCategory(SettingsCategory.NOTIFY) }
                CategoryRow(
                    category = SettingsCategory.DISPLAY,
                    summary = stringResource(
                        R.string.settings_display_summary,
                        ui.runtimeSettings.floatingWindowMode.localizedName(),
                        stringResource(
                            when (ui.virtualScreenState) {
                                VirtualScreenState.Idle -> R.string.virtual_screen_status_short_idle
                                VirtualScreenState.Starting -> R.string.virtual_screen_status_short_starting
                                is VirtualScreenState.Active -> R.string.virtual_screen_status_short_active
                                is VirtualScreenState.Failed -> R.string.virtual_screen_status_short_failed
                            },
                        ),
                    ),
                ) { onOpenCategory(SettingsCategory.DISPLAY) }
                CategoryRow(
                    category = SettingsCategory.ENV,
                    summary = stringResource(R.string.settings_env_summary, ui.globalEnv.size),
                ) { onOpenCategory(SettingsCategory.ENV) }
            }
            Spacer(Modifier.height(SectionGap))
            CategoryGroup(stringResource(R.string.settings_group_extensions)) {
                CategoryRow(
                    icon = Icons.Filled.AutoAwesome,
                    title = stringResource(R.string.settings_ai_agent),
                    summary = stringResource(
                        if (ui.aiConfig.isConfigured) R.string.settings_ai_configured else R.string.settings_ai_not_configured,
                    ),
                ) { onOpenAi() }
                CategoryRow(
                    icon = Icons.Filled.Hub,
                    title = stringResource(R.string.settings_mcp_server),
                    summary = if (ui.mcpStatus.running) {
                        stringResource(
                            R.string.settings_mcp_summary_running,
                            ui.mcpStatus.addresses.firstOrNull().orEmpty(),
                        )
                    } else {
                        stringResource(R.string.settings_mcp_summary_stopped)
                    },
                ) { onOpenMcp() }
            }
            Spacer(Modifier.height(SectionGap))
            CategoryGroup(stringResource(R.string.settings_group_security)) {
                CategoryRow(
                    icon = Icons.Filled.Lock,
                    title = stringResource(R.string.run_permissions_title),
                    summary = stringResource(R.string.settings_permissions_summary),
                ) { onOpenSetup() }
                CategoryRow(
                    category = SettingsCategory.UNLOCK,
                    summary = stringResource(
                        R.string.settings_unlock_summary,
                        stringResource(
                            if (ui.unlockSettings.enabled) R.string.common_on else R.string.common_off,
                        ),
                        stringResource(
                            if (ui.unlockSettings.hasCredential) {
                                R.string.settings_unlock_credential_configured
                            } else {
                                R.string.settings_unlock_credential_missing
                            },
                        ),
                    ),
                ) { onOpenCategory(SettingsCategory.UNLOCK) }
            }
            Spacer(Modifier.height(SectionGap))
            CategoryGroup(stringResource(R.string.settings_group_data)) {
                CategoryRow(
                    category = SettingsCategory.LOG,
                    summary = pluralStringResource(
                        R.plurals.settings_storage_summary,
                        ui.logSettings.retentionDays,
                        ui.logSettings.maxSizeMb,
                        ui.logSettings.retentionDays,
                    ),
                ) { onOpenCategory(SettingsCategory.LOG) }
                CategoryRow(
                    category = SettingsCategory.RECORD,
                    summary = stringResource(R.string.settings_record_summary),
                ) { onOpenCategory(SettingsCategory.RECORD) }
                CategoryRow(
                    category = SettingsCategory.BACKUP,
                    summary = stringResource(R.string.settings_backup_summary),
                ) { onOpenCategory(SettingsCategory.BACKUP) }
            }
            if (ui.developerSettings.unlocked) {
                Spacer(Modifier.height(SectionGap))
                CategoryGroup(stringResource(R.string.settings_group_developer)) {
                    CategoryRow(
                        category = SettingsCategory.DEVELOPER,
                        summary = stringResource(R.string.settings_developer_summary),
                    ) { onOpenCategory(SettingsCategory.DEVELOPER) }
                }
            }
            Spacer(Modifier.height(SectionGap))
            CategoryGroup {
                CategoryRow(
                    category = SettingsCategory.ABOUT,
                    summary = when (val state = ui.updateState) {
                        is UpdateState.Available ->
                            stringResource(R.string.update_available, state.info.versionName)

                        else -> stringResource(R.string.settings_version_short, BuildConfig.VERSION_NAME)
                    },
                ) { onOpenCategory(SettingsCategory.ABOUT) }
            }
            }
            Spacer(Modifier.height(SectionGap))
        }
    }
}

private data class SettingsSearchEntry(
    val title: String,
    val keywords: String,
    val icon: ImageVector,
    val open: () -> Unit,
)

@Composable
private fun settingsSearchEntries(
    onOpenCategory: (SettingsCategory) -> Unit,
    onOpenAi: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenSetup: () -> Unit,
): List<SettingsSearchEntry> = listOf(
    SettingsSearchEntry(
        stringResource(R.string.settings_language),
        "语言 界面语言 language locale",
        Icons.Filled.Settings,
    ) { onOpenCategory(SettingsCategory.GENERAL) },
    SettingsSearchEntry(
        stringResource(R.string.settings_dynamic_color),
        "主题 外观 颜色 动态取色 theme appearance color dynamic",
        Icons.Filled.Settings,
    ) { onOpenCategory(SettingsCategory.GENERAL) },
    SettingsSearchEntry(
        stringResource(R.string.ctrl_backends_title),
        "控制 后端 启停 无障碍 shizuku root adb control backend accessibility",
        Icons.Filled.Gamepad,
    ) { onOpenCategory(SettingsCategory.CONTROL) },
    SettingsSearchEntry(
        stringResource(R.string.ctrl_read_priority_title),
        "读取 优先级 read priority",
        Icons.Filled.Gamepad,
    ) { onOpenCategory(SettingsCategory.CONTROL) },
    SettingsSearchEntry(
        stringResource(R.string.ctrl_operate_priority_title),
        "操作 优先级 operate priority",
        Icons.Filled.Gamepad,
    ) { onOpenCategory(SettingsCategory.CONTROL) },
    SettingsSearchEntry(
        stringResource(R.string.ocr_section_title),
        "OCR 识别 在线 本地 recognition online local",
        Icons.Filled.Visibility,
    ) { onOpenCategory(SettingsCategory.VISION) },
    SettingsSearchEntry(
        stringResource(R.string.settings_vision),
        "截图 缩放 capture screenshot scale",
        Icons.Filled.Visibility,
    ) { onOpenCategory(SettingsCategory.VISION) },
    SettingsSearchEntry(
        stringResource(R.string.perm_timeout_title),
        "运行 超时 timeout run",
        Icons.Filled.Code,
    ) { onOpenCategory(SettingsCategory.RUN) },
    SettingsSearchEntry(
        stringResource(R.string.perm_wake_title),
        "保持唤醒 息屏 wake keep awake",
        Icons.Filled.Code,
    ) { onOpenCategory(SettingsCategory.RUN) },
    SettingsSearchEntry(
        stringResource(R.string.notif_section_channels),
        "通知 渠道 推送 notification channel push",
        Icons.Filled.Notifications,
    ) { onOpenCategory(SettingsCategory.NOTIFY) },
    SettingsSearchEntry(
        stringResource(R.string.notif_global_enabled),
        "通知 开关 声音 notification enable sound",
        Icons.Filled.Notifications,
    ) { onOpenCategory(SettingsCategory.NOTIFY) },
    SettingsSearchEntry(
        stringResource(R.string.settings_floating_window),
        "悬浮窗 悬浮控制条 display floating window",
        Icons.Filled.PictureInPicture,
    ) { onOpenCategory(SettingsCategory.DISPLAY) },
    SettingsSearchEntry(
        stringResource(R.string.virtual_screen_title),
        "虚拟屏 后台 virtual screen display",
        Icons.Filled.PictureInPicture,
    ) { onOpenCategory(SettingsCategory.DISPLAY) },
    SettingsSearchEntry(
        stringResource(R.string.unlock_enable),
        "解锁 自主解锁 unlock",
        Icons.Filled.LockOpen,
    ) { onOpenCategory(SettingsCategory.UNLOCK) },
    SettingsSearchEntry(
        stringResource(R.string.unlock_pattern_record),
        "解锁 密码 PIN 图案 录制 unlock password pattern record",
        Icons.Filled.LockOpen,
    ) { onOpenCategory(SettingsCategory.UNLOCK) },
    SettingsSearchEntry(
        stringResource(R.string.settings_env),
        "环境变量 变量 env variable",
        Icons.Filled.Key,
    ) { onOpenCategory(SettingsCategory.ENV) },
    SettingsSearchEntry(
        stringResource(R.string.storage_log_title),
        "日志 存储 上限 log storage size",
        Icons.AutoMirrored.Filled.Article,
    ) { onOpenCategory(SettingsCategory.LOG) },
    SettingsSearchEntry(
        stringResource(R.string.storage_retention_days),
        "日志 保留 天数 log retention days",
        Icons.AutoMirrored.Filled.Article,
    ) { onOpenCategory(SettingsCategory.LOG) },
    SettingsSearchEntry(
        stringResource(R.string.storage_auto_clean),
        "日志 自动清理 clean auto",
        Icons.AutoMirrored.Filled.Article,
    ) { onOpenCategory(SettingsCategory.LOG) },
    SettingsSearchEntry(
        stringResource(R.string.settings_record),
        "录制 回放 时间线 recording replay timeline",
        Icons.Filled.FiberManualRecord,
    ) { onOpenCategory(SettingsCategory.RECORD) },
    SettingsSearchEntry(
        stringResource(R.string.backup_export),
        "备份 导入 导出 backup import export",
        Icons.Filled.Backup,
    ) { onOpenCategory(SettingsCategory.BACKUP) },
    SettingsSearchEntry(
        stringResource(R.string.backup_reset_button),
        "重置 恢复默认 reset restore default",
        Icons.Filled.Backup,
    ) { onOpenCategory(SettingsCategory.BACKUP) },
    SettingsSearchEntry(
        stringResource(R.string.settings_developer),
        "开发者 调试 developer debug",
        Icons.Filled.Build,
    ) { onOpenCategory(SettingsCategory.DEVELOPER) },
    SettingsSearchEntry(
        stringResource(R.string.settings_about),
        "关于 版本 更新 about version update",
        Icons.Filled.Info,
    ) { onOpenCategory(SettingsCategory.ABOUT) },
    SettingsSearchEntry(
        stringResource(R.string.settings_ai_agent),
        "AI 智能体 模型 agent model",
        Icons.Filled.AutoAwesome,
    ) { onOpenAi() },
    SettingsSearchEntry(
        stringResource(R.string.settings_mcp_server),
        "MCP 服务器 令牌 token server",
        Icons.Filled.Hub,
    ) { onOpenMcp() },
    SettingsSearchEntry(
        stringResource(R.string.run_permissions_title),
        "权限 授权 能力 permission grant capability",
        Icons.Filled.Lock,
    ) { onOpenSetup() },
)


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDetailScreen(
    categoryKey: String,
    onBack: () -> Unit = {},
    onOpenSetup: () -> Unit = {},
    onOpenMcp: () -> Unit = {},
    onOpenRecordingDetail: (String) -> Unit = {},
    onOpenChannelEditor: (String?) -> Unit = {},
) {
    val category = SettingsCategory.fromKey(categoryKey)
    // 环境变量页需要拦截未保存修改；让顶栏返回复用页面注册的 requestBack。
    val envBackRequest = remember { mutableStateOf<(() -> Unit)?>(null) }
    SettingsDetailScaffold(
        title = category?.let { stringResource(it.titleRes) } ?: stringResource(R.string.settings_title),
        onBack = { envBackRequest.value?.invoke() ?: onBack() },
    ) {
        when (category) {
            SettingsCategory.GENERAL -> GeneralSettings()
            SettingsCategory.CONTROL -> ControlSettings(onOpenSetup = onOpenSetup)
            SettingsCategory.VISION -> VisionSettings()
            SettingsCategory.RUN -> RunSettings()
            SettingsCategory.NOTIFY -> NotificationSettingsPage(
                onOpenChannelEditor = onOpenChannelEditor,
            )
            SettingsCategory.DISPLAY -> DisplaySettings(onOpenSetup = onOpenSetup)
            SettingsCategory.UNLOCK -> UnlockSettingsPage(onOpenMcp = onOpenMcp)
            SettingsCategory.ENV -> GlobalEnvSettings(
                onBack = onBack,
                onBackRequest = { envBackRequest.value = it },
            )
            SettingsCategory.LOG -> StorageSettings()
            SettingsCategory.RECORD -> RecordingSettingsPage(onOpenDetail = onOpenRecordingDetail)
            SettingsCategory.BACKUP -> BackupSettingsScreen()
            SettingsCategory.DEVELOPER -> DeveloperSettings(onBack = onBack)
            SettingsCategory.ABOUT -> AboutSettings()
            null -> Text(
                stringResource(R.string.settings_unknown_category),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
private fun CategoryRow(
    category: SettingsCategory,
    summary: String,
    onClick: () -> Unit,
) = CategoryRow(
    icon = category.icon,
    title = stringResource(category.titleRes),
    summary = summary,
    onClick = onClick,
)

@Composable
private fun CategoryRow(title: String, summary: String, onClick: () -> Unit) =
    CategoryRow(icon = null, title = title, summary = summary, onClick = onClick)

@Composable
private fun CategoryRow(
    icon: ImageVector?,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
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
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(16.dp))
            }
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
    IzukiPageScaffold(
        title = { Text(title) },
        onBack = onBack,
    ) { padding ->
        PageColumn(modifier = Modifier.padding(padding).padding(horizontal = 16.dp)) {
            content()
            Spacer(Modifier.height(SectionGap))
        }
    }
}
