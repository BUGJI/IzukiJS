package com.benton.izukijs.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.controller.ControllerSettings
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.CapabilityGroup
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.localizedName
import com.benton.izukijs.ui.appViewModel

@Composable
internal fun ControlSettings() {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val controllerSettings = ui.controllerSettings
    val readyModes = ui.readyModes

    fun saveController(new: ControllerSettings) = viewModel.saveController(new)

    val capabilitiesByMode = viewModel.capabilitiesByMode()
    val readModes = ControlMode.entries.filter { mode ->
        mode in readyModes && capabilitiesByMode[mode].orEmpty().any { it.group == CapabilityGroup.READ }
    }
    val operateModes = ControlMode.entries.filter { mode ->
        mode in readyModes && capabilitiesByMode[mode].orEmpty().any { it.group == CapabilityGroup.OPERATE }
    }

    PriorityCard(
        title = stringResource(R.string.ctrl_read_priority_title),
        description = stringResource(R.string.ctrl_read_priority_desc),
        effective = viewModel.controllerMode(Capability.SCREENSHOT)?.localizedName(),
        availableModes = readModes,
        selectedMode = controllerSettings.readMode,
        onSelect = { saveController(controllerSettings.copy(readMode = it)) },
    )

    Spacer(Modifier.height(12.dp))
    PriorityCard(
        title = stringResource(R.string.ctrl_operate_priority_title),
        description = stringResource(R.string.ctrl_operate_priority_desc),
        effective = viewModel.controllerMode(Capability.GESTURE)?.localizedName(),
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
