package com.benton.izukijs.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.benton.izukijs.R
import com.benton.izukijs.i18n.AppLanguage
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.ocr.OcrProvider
import com.benton.izukijs.runtime.LogLevel

/** 控制模式的本地化名称（UI 层使用；模型内的 displayName 仍用于日志）。 */
@Composable
fun ControlMode.localizedName(): String = stringResource(
    when (this) {
        ControlMode.ACCESSIBILITY -> R.string.control_accessibility
        ControlMode.SHIZUKU -> R.string.control_shizuku
        ControlMode.HID -> R.string.control_hid
        ControlMode.ROOT -> R.string.control_root
    },
)

/** 控制能力的本地化名称。 */
@Composable
fun Capability.localizedName(): String = stringResource(
    when (this) {
        Capability.GESTURE -> R.string.capability_gesture
        Capability.ABSOLUTE_POINTER -> R.string.capability_absolute_pointer
        Capability.RELATIVE_POINTER -> R.string.capability_relative_pointer
        Capability.KEY -> R.string.capability_key
        Capability.TEXT -> R.string.capability_text
        Capability.SCREENSHOT -> R.string.capability_screenshot
        Capability.NODE_TREE -> R.string.capability_node_tree
        Capability.SHELL -> R.string.capability_shell
        Capability.GLOBAL_ACTION -> R.string.capability_global_action
        Capability.ROOT -> R.string.capability_root
    },
)

/** OCR 服务商的本地化名称。 */
@Composable
fun OcrProvider.localizedName(): String = stringResource(
    when (this) {
        OcrProvider.BAIDU -> R.string.ocr_provider_baidu
        OcrProvider.GOOGLE_VISION -> R.string.ocr_provider_google
        OcrProvider.CUSTOM -> R.string.ocr_provider_custom
    },
)

/** 日志级别的本地化名称。 */
@Composable
fun LogLevel.localizedName(): String = stringResource(
    when (this) {
        LogLevel.DEBUG -> R.string.log_level_debug
        LogLevel.INFO -> R.string.log_level_info
        LogLevel.SUCCESS -> R.string.log_level_success
        LogLevel.WARN -> R.string.log_level_warn
        LogLevel.ERROR -> R.string.log_level_error
    },
)

/** 应用语言的本地化名称。 */
@Composable
fun AppLanguage.localizedName(): String = stringResource(
    when (this) {
        AppLanguage.SYSTEM -> R.string.language_system
        AppLanguage.CHINESE -> R.string.language_chinese
        AppLanguage.ENGLISH -> R.string.language_english
    },
)
