package com.benton.izukijs.ui.settings

import com.benton.izukijs.ui.common.SectionGap
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.R
import com.benton.izukijs.ocr.OcrConfig
import com.benton.izukijs.ocr.OcrMode
import com.benton.izukijs.ocr.OcrProvider
import com.benton.izukijs.service.CaptureSettings
import com.benton.izukijs.ui.common.ChipFlow
import com.benton.izukijs.ui.common.LabeledField
import com.benton.izukijs.ui.common.SecretField
import com.benton.izukijs.ui.common.SectionCard
import com.benton.izukijs.ui.common.localizedName
import com.benton.izukijs.ui.appViewModel

@Composable
internal fun VisionSettings() {
    val viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val ocrConfig = ui.ocrConfig
    val captureSettings = ui.captureSettings

    fun updateOcr(new: OcrConfig) = viewModel.saveOcr(new)
    fun updateCapture(new: CaptureSettings) = viewModel.saveCapture(new)

    SectionCard(stringResource(R.string.capture_compress_title)) {
        Text(
            stringResource(R.string.capture_compress_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(SectionGap))
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
        Spacer(Modifier.height(SectionGap))
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

    Spacer(Modifier.height(SectionGap))
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
            Spacer(Modifier.height(SectionGap))
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
            Spacer(Modifier.height(SectionGap))
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
