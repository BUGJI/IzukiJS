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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Article
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
    LOG("log", R.string.settings_storage, Icons.Filled.Article),
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

    IzukiPageScaffold(
        title = { Text(stringResource(R.string.settings_title)) },
    ) { padding ->
        PageColumn(modifier = Modifier.padding(padding).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(SectionGap))
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
                    summary = stringResource(
                        R.string.settings_notify_summary,
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
                    summary = stringResource(
                        R.string.settings_storage_summary,
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
            Spacer(Modifier.height(SectionGap))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDetailScreen(
    categoryKey: String,
    onBack: () -> Unit = {},
    onOpenSetup: () -> Unit = {},
    onOpenMcp: () -> Unit = {},
    onOpenRecordingDetail: (String) -> Unit = {},
) {
    val category = SettingsCategory.fromKey(categoryKey)
    SettingsDetailScaffold(
        title = category?.let { stringResource(it.titleRes) } ?: stringResource(R.string.settings_title),
        onBack = onBack,
    ) {
        when (category) {
            SettingsCategory.GENERAL -> GeneralSettings()
            SettingsCategory.CONTROL -> ControlSettings(onOpenSetup = onOpenSetup)
            SettingsCategory.VISION -> VisionSettings()
            SettingsCategory.RUN -> RunSettings()
            SettingsCategory.NOTIFY -> NotificationSettingsPage()
            SettingsCategory.DISPLAY -> DisplaySettings(onOpenSetup = onOpenSetup)
            SettingsCategory.UNLOCK -> UnlockSettingsPage(onOpenMcp = onOpenMcp)
            SettingsCategory.ENV -> GlobalEnvSettings()
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
