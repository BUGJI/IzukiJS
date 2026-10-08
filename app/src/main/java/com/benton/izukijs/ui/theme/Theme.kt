package com.benton.izukijs.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

@Composable
fun IzukiJSTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 跟随系统壁纸取色（Material You），与 LSPosed 等应用保持一致；
    // 低版本系统自动回退到下方品牌配色。
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> IzukiDarkColors
        else -> IzukiLightColors
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
