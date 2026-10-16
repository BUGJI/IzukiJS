// 刻意复用 Material 组件库内置的 material-color-utilities（com.google.android.material.color.utilities）：
// 它与系统动态取色（Material You）同源，能保证亮 / 暗两套的对比度与层次一致，且不必引入额外依赖。
// 这些类在 Material AAR 中标注为 @RestrictTo(LIBRARY_GROUP)，直接调用会触发 lint 的 RestrictedApi
// （Severity=Error）→ 这里显式抑制并记录理由：行为等价、随 material 版本一并升级。
// 若将来 Material 公开该 API，应改用公开 API 并删除本抑制。
@file:Suppress("RestrictedApi")

package com.benton.izukijs.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.google.android.material.color.utilities.DynamicColor
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeTonalSpot

/**
 * 由用户选定的种子色派生整套 Material 3 配色。
 *
 * 复用 Material 组件库内置的 material-color-utilities（`SchemeTonalSpot` + `MaterialDynamicColors`），
 * 与系统动态取色同源，保证亮 / 暗两套的对比度与层次一致，无需额外依赖。
 */
fun seedColorScheme(seedArgb: Int, dark: Boolean): ColorScheme {
    val scheme = SchemeTonalSpot(Hct.fromInt(seedArgb), dark, /* contrastLevel = */ 0.0)
    val colors = MaterialDynamicColors()
    fun role(color: DynamicColor): Color = Color(color.getArgb(scheme))

    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = role(colors.primary()),
        onPrimary = role(colors.onPrimary()),
        primaryContainer = role(colors.primaryContainer()),
        onPrimaryContainer = role(colors.onPrimaryContainer()),
        inversePrimary = role(colors.inversePrimary()),
        secondary = role(colors.secondary()),
        onSecondary = role(colors.onSecondary()),
        secondaryContainer = role(colors.secondaryContainer()),
        onSecondaryContainer = role(colors.onSecondaryContainer()),
        tertiary = role(colors.tertiary()),
        onTertiary = role(colors.onTertiary()),
        tertiaryContainer = role(colors.tertiaryContainer()),
        onTertiaryContainer = role(colors.onTertiaryContainer()),
        error = role(colors.error()),
        onError = role(colors.onError()),
        errorContainer = role(colors.errorContainer()),
        onErrorContainer = role(colors.onErrorContainer()),
        background = role(colors.background()),
        onBackground = role(colors.onBackground()),
        surface = role(colors.surface()),
        onSurface = role(colors.onSurface()),
        surfaceVariant = role(colors.surfaceVariant()),
        onSurfaceVariant = role(colors.onSurfaceVariant()),
        surfaceTint = role(colors.surfaceTint()),
        inverseSurface = role(colors.inverseSurface()),
        inverseOnSurface = role(colors.inverseOnSurface()),
        outline = role(colors.outline()),
        outlineVariant = role(colors.outlineVariant()),
        scrim = role(colors.scrim()),
        surfaceDim = role(colors.surfaceDim()),
        surfaceBright = role(colors.surfaceBright()),
        surfaceContainerLowest = role(colors.surfaceContainerLowest()),
        surfaceContainerLow = role(colors.surfaceContainerLow()),
        surfaceContainer = role(colors.surfaceContainer()),
        surfaceContainerHigh = role(colors.surfaceContainerHigh()),
        surfaceContainerHighest = role(colors.surfaceContainerHighest()),
        primaryFixed = role(colors.primaryFixed()),
        primaryFixedDim = role(colors.primaryFixedDim()),
        onPrimaryFixed = role(colors.onPrimaryFixed()),
        onPrimaryFixedVariant = role(colors.onPrimaryFixedVariant()),
        secondaryFixed = role(colors.secondaryFixed()),
        secondaryFixedDim = role(colors.secondaryFixedDim()),
        onSecondaryFixed = role(colors.onSecondaryFixed()),
        onSecondaryFixedVariant = role(colors.onSecondaryFixedVariant()),
        tertiaryFixed = role(colors.tertiaryFixed()),
        tertiaryFixedDim = role(colors.tertiaryFixedDim()),
        onTertiaryFixed = role(colors.onTertiaryFixed()),
        onTertiaryFixedVariant = role(colors.onTertiaryFixedVariant()),
    )
}

/**
 * 纯黑（AMOLED）覆盖：背景 / 基础表面设为纯黑，容器保留极深灰层次，避免卡片与背景糊成一片。
 * 仅在深色配色上调用。
 */
fun ColorScheme.withAmoled(): ColorScheme = copy(
    background = AmoledBlack,
    surface = AmoledBlack,
    surfaceDim = AmoledBlack,
    surfaceContainerLowest = AmoledBlack,
    surfaceContainerLow = AmoledSurfaceLow,
    surfaceContainer = AmoledSurface,
    surfaceContainerHigh = AmoledSurfaceHigh,
    surfaceContainerHighest = AmoledSurfaceHighest,
)

/** AMOLED 纯黑配色常量。 */
val AmoledBlack = Color(0xFF000000)
val AmoledSurfaceLow = Color(0xFF0A0A0A)
val AmoledSurface = Color(0xFF121212)
val AmoledSurfaceHigh = Color(0xFF1A1A1A)
val AmoledSurfaceHighest = Color(0xFF222222)
