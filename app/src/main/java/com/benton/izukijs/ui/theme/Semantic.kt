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
            // 浅色主题下这些颜色会作为小字号文字直接叠在 surface / container 上，
            // 统一压深以满足普通文字 4.5:1 的对比度要求。
            success = Color(0xFF1B5E20),
            successContainer = Color(0xFFC8E6C9),
            onSuccessContainer = Color(0xFF0B3D0C),
            warning = Color(0xFF7A4A00),
            warningContainer = Color(0xFFFFDDB3),
            onWarningContainer = Color(0xFF3E2A00),
            logDebug = Color(0xFF5F6368),
            logInfo = Color(0xFF0B5AA6),
            logSuccess = Color(0xFF1B5E20),
            logWarn = Color(0xFF7A4A00),
            logError = Color(0xFFB3261E),
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
