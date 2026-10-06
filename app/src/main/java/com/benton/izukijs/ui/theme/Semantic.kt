package com.benton.izukijs.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Material3 没有 success / warning 这样有色义的语义色，这里补充一组，
 * 供「已就绪 / 运行中 / 日志级别」等状态统一使用，并随亮暗主题切换。
 */
@Immutable
data class IzukiExtraColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val logDebug: Color,
    val logInfo: Color,
    val logSuccess: Color,
    val logWarn: Color,
    val logError: Color,
) {
    companion object {
        fun light() = IzukiExtraColors(
            success = Color(0xFF2E7D32),
            successContainer = Color(0xFFC8E6C9),
            onSuccessContainer = Color(0xFF0B3D0C),
            warning = Color(0xFFB26A00),
            warningContainer = Color(0xFFFFDDB3),
            onWarningContainer = Color(0xFF3E2A00),
            logDebug = Color(0xFF6E6E6E),
            logInfo = Color(0xFF1976D2),
            logSuccess = Color(0xFF2E7D32),
            logWarn = Color(0xFFB26A00),
            logError = Color(0xFFD32F2F),
        )

        fun dark() = IzukiExtraColors(
            success = Color(0xFF81C784),
            successContainer = Color(0xFF1F4D22),
            onSuccessContainer = Color(0xFFB7F0B6),
            warning = Color(0xFFFFB74D),
            warningContainer = Color(0xFF5C3D00),
            onWarningContainer = Color(0xFFFFDDB3),
            logDebug = Color(0xFFB0B0B0),
            logInfo = Color(0xFF64B5F6),
            logSuccess = Color(0xFF81C784),
            logWarn = Color(0xFFFFB74D),
            logError = Color(0xFFEF9A9A),
        )
    }
}

val LocalIzukiExtraColors = staticCompositionLocalOf { IzukiExtraColors.light() }
