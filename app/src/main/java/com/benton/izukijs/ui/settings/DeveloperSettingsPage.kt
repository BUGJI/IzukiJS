package com.benton.izukijs.ui.settings

import com.benton.izukijs.ui.common.SectionGap
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.developer.DeveloperIntSpec
import com.benton.izukijs.developer.DeveloperSection
import com.benton.izukijs.developer.DeveloperSettingSpecs
import com.benton.izukijs.developer.DeveloperSettings
import com.benton.izukijs.ui.appViewModel
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.NumberField
import com.benton.izukijs.ui.common.SectionCard
import kotlinx.coroutines.launch

/**
 * 开发者选项：按 [DeveloperSettingSpecs] 注册表渲染分组，参数改动即时保存。
 *
 * 页面本身只负责展示与回写；范围校验与跨字段约束由 [DeveloperSettings.normalized] 统一处理。
 */
@Composable
internal fun DeveloperSettings(onBack: () -> Unit = {}) {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = ui.developerSettings
    val snackbar = LocalSnackbarController.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    SectionCard(stringResource(R.string.developer_warning_title)) {
        Text(
            stringResource(R.string.developer_warning_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    DeveloperSettingSpecs.sections.forEach { section ->
        Spacer(Modifier.height(SectionGap))
        DeveloperSectionCard(
            section = section,
            settings = settings,
            onChange = viewModel::saveDeveloperSettings,
        )
    }

    Spacer(Modifier.height(SectionGap))
    SectionCard {
        OutlinedButton(
            onClick = {
                viewModel.resetDeveloperSettings()
                scope.launch { snackbar.show(resources.getString(R.string.developer_reset_done)) }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.developer_reset))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                viewModel.setDeveloperUnlocked(false)
                scope.launch { snackbar.show(resources.getString(R.string.developer_hidden)) }
                onBack()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.developer_hide))
        }
    }
}

@Composable
private fun DeveloperSectionCard(
    section: DeveloperSection,
    settings: DeveloperSettings,
    onChange: (DeveloperSettings) -> Unit,
) {
    SectionCard(stringResource(section.titleRes)) {
        if (section.noteRes != null) {
            Text(
                stringResource(section.noteRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        section.specs.forEachIndexed { index, spec ->
            if (index > 0) Spacer(Modifier.height(SectionGap))
            DeveloperIntField(spec, settings, onChange)
        }
    }
}

@Composable
private fun DeveloperIntField(
    spec: DeveloperIntSpec,
    settings: DeveloperSettings,
    onChange: (DeveloperSettings) -> Unit,
) {
    Text(
        stringResource(spec.descRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(
            R.string.developer_range_hint,
            spec.default,
            spec.range.first,
            spec.range.last,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    NumberField(
        label = stringResource(spec.labelRes),
        value = spec.get(settings),
        range = spec.range,
        commitOnBlur = true,
        onChange = { value -> onChange(spec.set(settings, value)) },
    )
}
