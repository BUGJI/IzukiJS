package com.benton.izukijs

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.data.ThemeMode
import com.benton.izukijs.i18n.LanguagePreferences
import com.benton.izukijs.service.McpServerService
import com.benton.izukijs.ui.navigation.IzukiNavHost
import com.benton.izukijs.ui.theme.IzukiJSTheme

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguagePreferences.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as IzukiApp).container
        // 应用在前台启动时，若此前已启用 MCP 服务则恢复运行（前台服务需用户可见时启动）。
        if (container.mcpConfigRepository.current().enabled) {
            McpServerService.start(this)
        }
        setContent {
            val appearance by container.appearanceRepository.settings.collectAsStateWithLifecycle()
            val darkTheme = when (appearance.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            IzukiJSTheme(
                darkTheme = darkTheme,
                dynamicColor = appearance.dynamicColor,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    IzukiNavHost()
                }
            }
        }
    }
}
