package com.benton.izukijs

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benton.izukijs.core.perf.PerfMetrics
import com.benton.izukijs.core.permission.LocalNetworkPermission
import com.benton.izukijs.data.ThemeMode
import com.benton.izukijs.i18n.LanguagePreferences
import com.benton.izukijs.mcp.core.McpBindMode
import com.benton.izukijs.notification.NotificationIntents
import com.benton.izukijs.service.McpServerService
import com.benton.izukijs.ui.navigation.IzukiNavHost
import com.benton.izukijs.ui.pip.LocalIsInPip
import com.benton.izukijs.ui.pip.PipController
import com.benton.izukijs.ui.pip.PipHost
import com.benton.izukijs.ui.pip.PipRequest
import com.benton.izukijs.ui.pip.VirtualScreenPreviewHost
import com.benton.izukijs.ui.theme.IzukiJSTheme
import com.benton.izukijs.virtualscreen.VirtualScreenState

class MainActivity : ComponentActivity(), PipHost {

    @Volatile
    override var pipRequest: PipRequest? = null

    private var isInPip by mutableStateOf(false)
    private var requestedRoute by mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguagePreferences.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PerfMetrics.bindWindow(window)
        requestedRoute = NotificationIntents.routeFrom(intent)
        val container = (application as IzukiApp).container
        // 启动窗口使用系统 DayNight 主题，而应用内可强制深色。先按持久化主题铺底色，
        // 避免“强制深色 + 系统浅色”时先闪一帧白底再切暗。
        val appearance = container.appearanceRepository.current()
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val forceDark = when (appearance.themeMode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        val splashColor = when {
            forceDark && appearance.amoledBackground -> 0xFF000000.toInt()
            forceDark -> 0xFF1A1C1E.toInt()
            else -> 0xFFFAF9FC.toInt()
        }
        window.setBackgroundDrawable(ColorDrawable(splashColor))
        // 应用在前台启动时，若此前已启用 MCP 服务则恢复运行（前台服务需用户可见时启动）。
        // 已在运行时不再重复下发启动 Intent，避免语言切换 / 配置重建导致监听器无谓重启、客户端断连。
        val mcpConfig = container.mcpConfigRepository.current()
        val needsLocalNetwork = mcpConfig.bindMode == McpBindMode.LAN &&
            !LocalNetworkPermission.isGranted(this)
        if (mcpConfig.enabled &&
            !needsLocalNetwork &&
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
                // 强制亮 / 暗主题时，系统栏图标也要跟随应用主题，而不是系统夜间模式。
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
                val virtualState by container.virtualScreenManager.state.collectAsStateWithLifecycle()
                CompositionLocalProvider(LocalIsInPip provides isInPip) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            IzukiNavHost(
                                requestedRoute = requestedRoute,
                                onRequestedRouteHandled = { requestedRoute = null },
                            )
                        }
                        if (virtualState is VirtualScreenState.Active || isInPip) {
                            VirtualScreenPreviewHost(modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationIntents.routeFrom(intent)?.let { requestedRoute = it }
    }

    override fun onDestroy() {
        PerfMetrics.unbindWindow(window)
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val request = pipRequest ?: return
        PipController.enterNow(this, request)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip = isInPictureInPictureMode
    }
}
