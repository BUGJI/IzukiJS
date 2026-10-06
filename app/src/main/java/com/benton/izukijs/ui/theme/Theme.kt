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
    // 关闭动态取色以保持品牌配色一致；如需跟随系统壁纸可改为 true。
    dynamicColor: Boolean = false,
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
