package com.benton.izukijs.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.BuildConfig
import com.benton.izukijs.R
import com.benton.izukijs.ui.appViewModel
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SectionGap
import com.benton.izukijs.ui.common.StatusRow
import com.benton.izukijs.ui.common.SwitchRow
import com.benton.izukijs.ui.update.ReleaseNotesContent
import com.benton.izukijs.ui.update.UpdateProgress
import com.benton.izukijs.ui.update.formatUpdateSize
import com.benton.izukijs.ui.update.updateFailureMessage
import com.benton.izukijs.update.UpdateChecker
import com.benton.izukijs.update.UpdateInfo
import com.benton.izukijs.update.UpdateMethod
import com.benton.izukijs.update.UpdateSettings
import com.benton.izukijs.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 连续点击版本号解锁开发者选项：两次点击间隔超过该值时重新计数。 */
private const val DEVELOPER_TAP_RESET_MS = 2_000L

/** 第几次点击开始给出剩余次数提示。 */
private const val DEVELOPER_HINT_TAP = 4

/** 解锁所需点击次数。 */
private const val DEVELOPER_UNLOCK_TAP = 7

private const val REPO_URL = "https://github.com/${UpdateChecker.REPO}"
private const val ISSUES_URL = "$REPO_URL/issues"
private const val CHANGELOG_URL = "$REPO_URL/blob/main/CHANGELOG.md"
private const val DOCS_URL = "$REPO_URL/tree/main/docs"
private const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

@Composable
internal fun AboutSettings() {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarController.current
    val resources = LocalResources.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var taps by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var showLicense by rememberSaveable { mutableStateOf(false) }

    fun showSnackbar(messageRes: Int) {
        scope.launch { snackbar.show(resources.getString(messageRes)) }
    }

    fun copyToClipboard(text: String, messageRes: Int) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("IzukiJS", text))
        showSnackbar(messageRes)
    }

    fun openUrl(url: String) {
        runCatching { uriHandler.openUri(url) }
            .onFailure { showSnackbar(R.string.about_link_failed) }
    }

    fun onVersionTap() {
        if (ui.developerSettings.unlocked) {
            showSnackbar(R.string.developer_unlock_already)
            return
        }
        val now = SystemClock.elapsedRealtime()
        taps = if (now - lastTapAt > DEVELOPER_TAP_RESET_MS) 1 else taps + 1
        lastTapAt = now
        when {
            taps >= DEVELOPER_UNLOCK_TAP -> {
                viewModel.setDeveloperUnlocked(true)
                taps = 0
                showSnackbar(R.string.developer_unlocked)
            }

            taps >= DEVELOPER_HINT_TAP -> {
                val remaining = DEVELOPER_UNLOCK_TAP - taps
                scope.launch {
                    snackbar.show(resources.getString(R.string.developer_unlock_hint, remaining))
                }
            }
        }
    }

    val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "—"
    val buildInfo = listOf(
        stringResource(R.string.about_build_package) to context.packageName,
        stringResource(R.string.about_build_version) to
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        stringResource(R.string.about_build_type) to BuildConfig.BUILD_TYPE,
        stringResource(R.string.about_build_abi) to abi,
        stringResource(R.string.about_build_android) to
            "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    )
    val versionText = stringResource(
        R.string.about_version,
        BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE,
    )
    val copyVersionLabel = stringResource(R.string.about_copy_version)
    val iconSizePx = with(LocalDensity.current) { 56.dp.roundToPx() }
    // 图标解码放到 IO，避免首次进入关于页时在组合线程做 Drawable -> Bitmap 转换。
    val appIcon by produceState<Bitmap?>(initialValue = null, context, iconSizePx) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ContextCompat.getDrawable(context, R.mipmap.ic_launcher_round)
                    ?.toBitmap(width = iconSizePx, height = iconSizePx)
            }.getOrNull()
        }
    }

    AboutHeaderCard(
        appIcon = appIcon,
        versionText = versionText,
        copyLabel = copyVersionLabel,
        onTap = { onVersionTap() },
        onCopy = { copyToClipboard(versionText, R.string.about_version_copied) },
    )

    Spacer(Modifier.height(SectionGap))
    UpdateSettingsSection(
        ui = ui,
        viewModel = viewModel,
        onOpenUrl = ::openUrl,
    )

    Spacer(Modifier.height(SectionGap))
    SectionCard(stringResource(R.string.about_links)) {
        LinkRow(stringResource(R.string.about_github)) { openUrl(REPO_URL) }
        LinkRow(stringResource(R.string.about_issues)) { openUrl(ISSUES_URL) }
        LinkRow(stringResource(R.string.about_changelog)) { openUrl(CHANGELOG_URL) }
        LinkRow(stringResource(R.string.about_docs)) { openUrl(DOCS_URL) }
        LinkRow(stringResource(R.string.about_license)) { showLicense = true }
    }

    Spacer(Modifier.height(SectionGap))
    SectionCard(
        title = stringResource(R.string.about_build_info),
        trailing = {
            TextButton(
                onClick = {
                    val text = buildInfo.joinToString("\n") { (label, value) -> "$label: $value" }
                    copyToClipboard(text, R.string.about_build_copied)
                },
            ) { Text(stringResource(R.string.about_copy_build)) }
        },
    ) {
        buildInfo.forEachIndexed { index, (label, value) ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            StatusRow(label, value)
        }
    }

    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text(stringResource(R.string.about_license_title)) },
            text = { Text(stringResource(R.string.about_license_summary)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLicense = false
                        openUrl(LICENSE_URL)
                    },
                ) { Text(stringResource(R.string.about_license_view)) }
            },
            dismissButton = {
                TextButton(onClick = { showLicense = false }) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AboutHeaderCard(
    appIcon: Bitmap?,
    versionText: String,
    copyLabel: String,
    onTap: () -> Unit,
    onCopy: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon.asImageBitmap(),
                        contentDescription = stringResource(R.string.about_app_icon),
                        modifier = Modifier.size(56.dp),
                    )
                } else {
                    Icon(
                        Icons.Filled.Code,
                        contentDescription = stringResource(R.string.about_app_icon),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.about_name), style = MaterialTheme.typography.titleLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .combinedClickable(
                            role = Role.Button,
                            onLongClickLabel = copyLabel,
                            onLongClick = onCopy,
                            onClick = onTap,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        versionText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.about_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun UpdateSettingsSection(
    ui: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenUrl: (String) -> Unit,
) {
    val settings = ui.updateSettings
    val state = ui.updateState
    val info = ui.updateAvailableInfo
    val lastCheckedAt = ui.updateLastCheckedAt

    fun update(new: UpdateSettings) = viewModel.saveUpdateSettings(new)

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.retryInstallAfterPermission()
    }

    SectionCard(stringResource(R.string.update_section_title)) {
        Text(
            stringResource(R.string.update_current_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (lastCheckedAt == null) {
                stringResource(R.string.update_never_checked)
            } else {
                stringResource(R.string.update_last_checked, relativeTime(lastCheckedAt))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        UpdateStatusLine(state)

        if (info != null && state !is UpdateState.UpToDate) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.update_release_notes),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            ReleaseNotesContent(info.changelog)
            if (info.apkSizeBytes > 0L) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.update_apk_size, formatUpdateSize(info.apkSizeBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onOpenUrl(info.releaseUrl) }) {
                Text(stringResource(R.string.update_view_release))
            }
        }

        Spacer(Modifier.height(SectionGap))
        UpdateActions(
            state = state,
            info = info,
            method = settings.method,
            onCheck = viewModel::checkForUpdates,
            onDownload = viewModel::downloadAndInstallUpdate,
            onOpenGitHub = viewModel::openUpdateOnGitHub,
            onInstall = viewModel::installDownloadedUpdate,
            onCancel = viewModel::cancelUpdateDownload,
            onRetry = viewModel::retryUpdate,
            onOpenPermission = viewModel::openInstallPermissionSettings,
        )

        if (info != null && settings.method == UpdateMethod.IN_APP && info.apkUrl.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.update_no_asset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(SectionGap))
        SwitchRow(
            label = stringResource(R.string.update_auto_check),
            description = stringResource(R.string.update_auto_check_desc),
            checked = settings.autoCheck,
        ) { update(settings.copy(autoCheck = it)) }

        Spacer(Modifier.height(SectionGap))
        SwitchRow(
            label = stringResource(R.string.update_wifi_only),
            description = stringResource(R.string.update_wifi_only_desc),
            checked = settings.wifiOnly,
        ) { update(settings.copy(wifiOnly = it)) }

        Spacer(Modifier.height(SectionGap))
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateActions(
    state: UpdateState,
    info: UpdateInfo?,
    method: UpdateMethod,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onOpenGitHub: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenPermission: () -> Unit,
) {
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state !is UpdateState.Downloading) {
            Button(onClick = onCheck, enabled = !busy) {
                Text(stringResource(R.string.update_check_button))
            }
        }
        when (state) {
            is UpdateState.Downloading -> OutlinedButton(onClick = onCancel) {
                Text(stringResource(R.string.common_cancel))
            }

            is UpdateState.InstallPermissionRequired -> Button(onClick = onOpenPermission) {
                Text(stringResource(R.string.update_open_permission_settings))
            }

            is UpdateState.InstallPending -> OutlinedButton(onClick = onInstall) {
                Text(stringResource(R.string.update_reopen_installer))
            }

            else -> if (
                info != null && !busy &&
                state !is UpdateState.CheckFailed && state !is UpdateState.DownloadFailed
            ) {
                val inApp =
                    method == UpdateMethod.IN_APP && !info.apkUrl.isNullOrBlank()
                Button(onClick = if (inApp) onDownload else onOpenGitHub) {
                    Text(
                        stringResource(
                            if (inApp) R.string.update_download else R.string.update_open_github,
                        ),
                    )
                }
            }
        }
        if (state is UpdateState.CheckFailed || state is UpdateState.DownloadFailed) {
            OutlinedButton(onClick = onRetry) {
                Text(stringResource(R.string.update_retry))
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

        is UpdateState.Downloading -> UpdateProgress(state.progress)

        UpdateState.InstallPermissionRequired -> Text(
            stringResource(R.string.update_need_install_permission),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        UpdateState.InstallPending -> Text(
            stringResource(R.string.update_install_pending),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is UpdateState.CheckFailed -> Text(
            stringResource(R.string.update_check_failed, updateFailureMessage(state.failure)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        is UpdateState.DownloadFailed -> Text(
            stringResource(R.string.update_download_failed, updateFailureMessage(state.failure)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
