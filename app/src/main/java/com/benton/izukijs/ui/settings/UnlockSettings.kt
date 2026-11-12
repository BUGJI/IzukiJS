package com.benton.izukijs.ui.settings

import com.benton.izukijs.ui.common.SectionGap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.model.UnlockMethod
import com.benton.izukijs.security.SecretCipher
import com.benton.izukijs.service.UnlockRecordingService
import com.benton.izukijs.service.UnlockRecordingState
import com.benton.izukijs.model.UnlockResult
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.ConfirmDialog
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.SecretField
import com.benton.izukijs.ui.common.StatusRow
import com.benton.izukijs.ui.common.SwitchRow
import com.benton.izukijs.ui.appViewModel
import com.benton.izukijs.ui.rememberAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UnlockSettingsPage(onOpenMcp: () -> Unit = {}) {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val container = rememberAppContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarController.current
    val unlockSettings = ui.unlockSettings
    val mcpConfig = ui.mcpConfig
    val readyModes = ui.readyModes
    val recordingState by container.unlockGestureRecorder.state.collectAsStateWithLifecycle()
    val unlockReady = ControlMode.SHIZUKU in readyModes ||
        ControlMode.ADB in readyModes ||
        ControlMode.ROOT in readyModes

    val pinPlain = remember(unlockSettings.pinCipher) {
        SecretCipher.decrypt(unlockSettings.pinCipher)
    }
    var showRecordConfirm by remember { mutableStateOf(false) }
    var testRunning by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<UnlockResult?>(null) }
    val lockedHint = stringResource(R.string.unlock_test_locked_hint)
    val recordingActive = recordingState is UnlockRecordingState.Preparing ||
        recordingState is UnlockRecordingState.Recording ||
        recordingState is UnlockRecordingState.Saving

    SectionCard(stringResource(R.string.settings_unlock)) {
        SwitchRow(
            label = stringResource(R.string.unlock_enable),
            description = stringResource(
                if (unlockReady) R.string.unlock_desc else R.string.unlock_backend_missing,
            ),
            checked = unlockSettings.enabled,
            // 后端暂时不可用时仍允许关闭，避免安全敏感功能被锁死在开启状态。
            enabled = unlockReady || unlockSettings.enabled,
        ) { checked ->
            viewModel.updateUnlock { it.copy(enabled = checked) }
        }

        if (unlockSettings.enabled) {
            Spacer(Modifier.height(SectionGap))
            Text(
                stringResource(R.string.unlock_method),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            ChipFlow {
                UnlockMethod.entries.forEach { method ->
                    FilterChip(
                        selected = unlockSettings.method == method,
                        onClick = {
                            testResult = null
                            viewModel.updateUnlock { it.copy(method = method) }
                        },
                        label = {
                            Text(
                                stringResource(
                                    when (method) {
                                        UnlockMethod.SWIPE -> R.string.unlock_method_swipe
                                        UnlockMethod.PIN -> R.string.unlock_method_pin
                                        UnlockMethod.PATTERN -> R.string.unlock_method_pattern
                                    },
                                ),
                            )
                        },
                    )
                }
            }

            when (unlockSettings.method) {
                UnlockMethod.SWIPE -> Unit

                UnlockMethod.PIN -> {
                    SecretField(
                        label = stringResource(R.string.unlock_pin_label),
                        value = pinPlain,
                        commitOnBlur = true,
                        keyboardType = KeyboardType.NumberPassword,
                        onChange = { input ->
                            testResult = null
                            viewModel.setUnlockPin(input.filter { it.isDigit() })
                        },
                    )
                    Text(
                        stringResource(R.string.unlock_pin_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                UnlockMethod.PATTERN -> {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(
                            when {
                                recordingState is UnlockRecordingState.Saving ->
                                    R.string.unlock_recording_saving

                                recordingState is UnlockRecordingState.Success ->
                                    R.string.unlock_recording_success

                                recordingState is UnlockRecordingState.Failed ->
                                    R.string.unlock_recording_failed_short

                                recordingActive -> R.string.unlock_recording
                                unlockSettings.hasPattern -> R.string.unlock_pattern_recorded
                                else -> R.string.unlock_pattern_missing
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    (recordingState as? UnlockRecordingState.Failed)?.let { failed ->
                        Text(
                            stringResource(unlockResultRes(failed.result)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = unlockReady && !recordingActive,
                            onClick = { showRecordConfirm = true },
                        ) {
                            Text(stringResource(R.string.unlock_pattern_record))
                        }
                        TextButton(
                            enabled = !recordingActive,
                            onClick = {
                                viewModel.clearUnlockPattern()
                                container.unlockGestureRecorder.resetState()
                            },
                        ) {
                            Text(stringResource(R.string.unlock_pattern_clear))
                        }
                    }
                    Text(
                        stringResource(R.string.unlock_pattern_record_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(SectionGap))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    enabled = !testRunning && unlockReady,
                    onClick = {
                        testRunning = true
                        testResult = null
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    container.screenUnlockController.testUnlock()
                                }
                            }.getOrElse { UnlockResult.UNSUPPORTED }
                            testRunning = false
                            testResult = result
                            if (result == UnlockResult.ALREADY_UNLOCKED) {
                                snackbar.show(lockedHint)
                            }
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (testRunning) {
                                R.string.unlock_testing
                            } else {
                                R.string.unlock_test
                            },
                        ),
                    )
                }
                testResult?.let { result ->
                    Text(
                        stringResource(unlockResultRes(result)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.unlock_risk),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    Spacer(Modifier.height(SectionGap))
    SectionCard(stringResource(R.string.unlock_mcp_section)) {
        Text(
            stringResource(R.string.unlock_mcp_section_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        StatusRow(
            label = stringResource(R.string.unlock_mcp_service),
            value = stringResource(if (mcpConfig.enabled) R.string.common_on else R.string.common_off),
        )
        Spacer(Modifier.height(4.dp))
        StatusRow(
            label = stringResource(R.string.unlock_mcp_allow_tool),
            value = stringResource(if (mcpConfig.allowUnlock) R.string.common_on else R.string.common_off),
        )
        Spacer(Modifier.height(4.dp))
        StatusRow(
            label = stringResource(R.string.unlock_mcp_auto_unlock),
            value = stringResource(if (mcpConfig.autoUnlock) R.string.common_on else R.string.common_off),
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onOpenMcp) {
            Text(stringResource(R.string.unlock_mcp_open_settings))
        }
    }

    if (showRecordConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.unlock_record_confirm_title),
            message = stringResource(R.string.unlock_record_confirm_text),
            confirmLabel = stringResource(R.string.unlock_record_start),
            onConfirm = {
                showRecordConfirm = false
                container.unlockGestureRecorder.resetState()
                UnlockRecordingService.start(context)
            },
            onDismiss = { showRecordConfirm = false },
        )
    }
}

internal fun unlockResultRes(result: UnlockResult): Int = when (result) {
    UnlockResult.OK -> R.string.unlock_result_ok
    UnlockResult.ALREADY_UNLOCKED -> R.string.unlock_result_already
    UnlockResult.DISABLED -> R.string.unlock_result_disabled
    UnlockResult.NO_BACKEND -> R.string.unlock_result_no_backend
    UnlockResult.LOCK_FAILED -> R.string.unlock_result_lock_failed
    UnlockResult.WAKE_FAILED -> R.string.unlock_result_wake_failed
    UnlockResult.DISMISS_FAILED -> R.string.unlock_result_dismiss_failed
    UnlockResult.CREDENTIAL_REQUIRED -> R.string.unlock_result_credential_required
    UnlockResult.CREDENTIAL_INVALID -> R.string.unlock_result_credential_invalid
    UnlockResult.CREDENTIAL_REJECTED -> R.string.unlock_result_credential_rejected
    UnlockResult.PATTERN_SCREEN_MISMATCH -> R.string.unlock_result_pattern_mismatch
    UnlockResult.UNSUPPORTED -> R.string.unlock_result_unsupported
    UnlockResult.TIMEOUT -> R.string.unlock_result_timeout
    UnlockResult.NO_INPUT -> R.string.unlock_result_no_input
    UnlockResult.RECORDING_TIMEOUT -> R.string.unlock_result_recording_timeout
    UnlockResult.CANCELLED -> R.string.unlock_result_cancelled
}
