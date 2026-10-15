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
        // 已在运行时不再重复下发启动 Intent，避免语言切换 / 配置重建导致监听器无谓重启、客户端断连。
        if (container.mcpConfigRepository.current().enabled &&
            !container.mcpServerController.status.value.running
        ) {
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
                seedColor = appearance.seedArgb,
                amoled = appearance.amoledBackground,
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
