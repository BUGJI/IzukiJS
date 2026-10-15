package com.benton.izukijs.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun IzukiJSTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 跟随系统壁纸取色（Material You），与 LSPosed 等应用保持一致；
    // 低版本系统自动回退到下方品牌配色。
    dynamicColor: Boolean = true,
    // 用户自定义种子色；null 表示使用内置品牌色板。动态取色优先级高于此。
    seedColor: Int? = null,
    // 深色主题下使用纯黑背景（AMOLED）。
    amoled: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val baseScheme = remember(context, dynamicColor, seedColor, darkTheme) {
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            seedColor != null -> seedColorScheme(seedColor, darkTheme)
            darkTheme -> IzukiDarkColors
            else -> IzukiLightColors
        }
    }
    val colorScheme = remember(baseScheme, darkTheme, amoled) {
        if (darkTheme && amoled) baseScheme.withAmoled() else baseScheme
    }
    val extraColors = if (darkTheme) IzukiExtraColors.dark() else IzukiExtraColors.light()

    CompositionLocalProvider(LocalIzukiExtraColors provides extraColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
