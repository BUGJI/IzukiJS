package com.benton.izukijs.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.benton.izukijs.R
import com.benton.izukijs.update.UpdateFailure
import java.util.Locale

/** 把稳定的失败原因映射为当前语言的提示；原始异常文本不进入界面。 */
@Composable
fun updateFailureMessage(failure: UpdateFailure): String = when (failure) {
    UpdateFailure.Network -> stringResource(R.string.update_failure_network)
    UpdateFailure.RateLimited -> stringResource(R.string.update_failure_rate_limited)
    UpdateFailure.NotFound -> stringResource(R.string.update_failure_not_found)
    is UpdateFailure.Server -> stringResource(R.string.update_failure_server, failure.code)
    UpdateFailure.Parse -> stringResource(R.string.update_failure_parse)
    UpdateFailure.WifiRequired -> stringResource(R.string.update_failure_wifi_required)
    UpdateFailure.SizeMismatch -> stringResource(R.string.update_failure_size_mismatch)
    UpdateFailure.MissingFile -> stringResource(R.string.update_error_apk_missing)
    UpdateFailure.InstallFailed -> stringResource(R.string.update_error_install_launch)
    UpdateFailure.Unknown -> stringResource(R.string.update_failure_unknown)
}

/** Release notes 可能很长，限制高度并允许选中复制，避免撑坏对话框或设置卡片。 */
@Composable
fun ReleaseNotesContent(changelog: String, modifier: Modifier = Modifier) {
    if (changelog.isBlank()) {
        Text(
            stringResource(R.string.update_dialog_no_changelog),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    SelectionContainer(modifier = modifier) {
        Text(
            changelog,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

/** 安装包体积统一格式化，页面与弹窗保持一致。 */
fun formatUpdateSize(bytes: Long): String = when {
    bytes >= 1_048_576L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1_024.0)
    else -> "$bytes B"
}

/** 下载进度：未知总大小时使用不确定进度条。 */
@Composable
fun UpdateProgress(progress: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (progress < 0) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (progress < 0) {
                stringResource(R.string.update_downloading_unknown)
            } else {
                stringResource(R.string.update_downloading, progress)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
