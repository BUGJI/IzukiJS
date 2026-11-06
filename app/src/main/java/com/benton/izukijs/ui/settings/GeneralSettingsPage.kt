package com.benton.izukijs.ui.settings

import com.benton.izukijs.ui.common.SectionGap
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.data.AppearanceSettings
import com.benton.izukijs.data.EditorSettings
import com.benton.izukijs.data.ThemeMode
import com.benton.izukijs.i18n.AppLanguage
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SwitchRow
import com.benton.izukijs.ui.common.findActivity
import com.benton.izukijs.ui.common.localizedName
import com.benton.izukijs.ui.appViewModel
import kotlinx.coroutines.launch

@Composable
internal fun GeneralSettings() {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val appearance = ui.appearance
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun update(new: AppearanceSettings) = viewModel.saveAppearance(new)

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
        Spacer(Modifier.height(SectionGap))
        SwitchRow(
            label = stringResource(R.string.settings_dynamic_color),
            description = stringResource(
                if (dynamicColorSupported) {
                    R.string.settings_dynamic_color_desc
                } else {
                    R.string.settings_dynamic_color_unsupported
                },
            ),
            checked = dynamicColorSupported && appearance.dynamicColor,
            enabled = dynamicColorSupported,
            onChange = { update(appearance.copy(dynamicColor = it)) },
        )
        Spacer(Modifier.height(SectionGap))
        SwitchRow(
            label = stringResource(R.string.settings_amoled_title),
            description = stringResource(R.string.settings_amoled_desc),
            checked = appearance.amoledBackground,
            onChange = { update(appearance.copy(amoledBackground = it)) },
        )

        Spacer(Modifier.height(SectionGap))
        HorizontalDivider()
        Spacer(Modifier.height(SectionGap))
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
        Spacer(Modifier.height(SectionGap))
        val customColorEnabled = !appearance.dynamicColor
        ColorSwatchRow(
            selectedArgb = appearance.seedArgb,
            enabled = customColorEnabled,
            onSelect = { update(appearance.copy(seedArgb = it)) },
        )
        Spacer(Modifier.height(SectionGap))
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

    Spacer(Modifier.height(SectionGap))
    LanguageSettings()

    Spacer(Modifier.height(SectionGap))
    EditorSettingsSection()
}

@Composable
internal fun NotificationSettingsSection() {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPostNotifications(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = hasPostNotifications(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SectionCard(stringResource(R.string.settings_notifications)) {
        Text(
            stringResource(R.string.settings_notifications_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            label = stringResource(R.string.settings_progress_notification),
            description = stringResource(R.string.settings_progress_notification_desc),
            checked = ui.runtimeSettings.progressNotification,
            onChange = {
                viewModel.saveRuntimeSettings(
                    ui.runtimeSettings.copy(progressNotification = it),
                )
            },
        )
        if (!granted) {
            Spacer(Modifier.height(SectionGap))
            Text(
                stringResource(R.string.settings_notification_permission_denied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(
                                    android.provider.Settings.EXTRA_APP_PACKAGE,
                                    context.packageName,
                                ),
                        )
                    }
                },
            ) {
                Text(stringResource(R.string.settings_notification_permission_action))
            }
        }
    }
}

private fun hasPostNotifications(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

@Composable
private fun LanguageSettings() {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
                    selected = ui.language == option,
                    onClick = {
                        if (ui.language != option) {
                            viewModel.saveLanguage(option)
                            context.findActivity()?.recreate()
                        }
                    },
                    label = { Text(option.localizedName()) },
                )
            }
        }
    }
}

@Composable
private fun EditorSettingsSection() {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val editorSettings = ui.editorSettings

    fun update(new: EditorSettings) = viewModel.saveEditorSettings(new)

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
        Spacer(Modifier.height(SectionGap))
        SwitchRow(
            label = stringResource(R.string.editor_auto_save),
            description = stringResource(R.string.editor_auto_save_desc),
            checked = editorSettings.autoSave,
            onChange = { update(editorSettings.copy(autoSave = it)) },
        )
    }
}
