package com.benton.izukijs.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.benton.izukijs.R
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.benton.izukijs.ui.ai.AiAgentScreen
import com.benton.izukijs.ui.editor.EditorScreen
import com.benton.izukijs.ui.hid.HidSetupScreen
import com.benton.izukijs.ui.inspector.LayoutInspectorScreen
import com.benton.izukijs.ui.logs.LogsScreen
import com.benton.izukijs.ui.permission.PermissionScreen
import com.benton.izukijs.ui.run.RunScreen
import com.benton.izukijs.ui.schedule.ScheduleScreen
import com.benton.izukijs.ui.scripts.ScriptsScreen
import com.benton.izukijs.ui.settings.SettingsCategory
import com.benton.izukijs.ui.settings.SettingsDetailScreen
import com.benton.izukijs.ui.settings.SettingsScreen

object Routes {
    const val ARG_SCRIPT_ID = "scriptId"
    const val ARG_SETTINGS_CATEGORY = "category"
    const val RUN = "run"
    const val SCRIPTS = "scripts"
    const val LOGS = "logs"
    const val SETTINGS = "settings"
    const val SETTINGS_DETAIL = "settings/{$ARG_SETTINGS_CATEGORY}"
    const val EDITOR = "editor/{$ARG_SCRIPT_ID}"
    const val SETUP = "setup"
    const val INSPECTOR = "inspector"
    const val SCHEDULE = "schedule"
    const val HID = "hid"
    const val AI = "ai"

    fun editor(scriptId: String) = "editor/$scriptId"

    fun settingsDetail(category: SettingsCategory) = "settings/${category.key}"
}

private enum class BottomItem(
    val route: String,
    @StringRes val labelRes: Int,
) {
    RUN(Routes.RUN, R.string.nav_run),
    SCRIPTS(Routes.SCRIPTS, R.string.nav_scripts),
    LOGS(Routes.LOGS, R.string.nav_logs),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings),
}

/** 底部导航图标；日志使用专用矢量图，避免与「菜单」图标语义混淆。 */
@Composable
private fun BottomItem.icon(): ImageVector = when (this) {
    BottomItem.RUN -> Icons.Filled.PlayArrow
    BottomItem.SCRIPTS -> Icons.AutoMirrored.Filled.List
    BottomItem.LOGS -> ImageVector.vectorResource(R.drawable.ic_nav_logs)
    BottomItem.SETTINGS -> Icons.Filled.Settings
}

private val BOTTOM_ROUTES = BottomItem.entries.map { it.route }.toSet()

/** 宽屏判定阈值：>= 600dp 时改用侧边 NavigationRail。 */
private val WIDE_LAYOUT_MIN_WIDTH = 600.dp

/** 悬浮底栏滑入 / 滑出的动画时长。 */
private const val BAR_ANIMATION_MS = 250

/** 悬浮底栏占位高度（NavigationBar 自身高度，系统导航栏 inset 由各页面 Scaffold 处理）。 */
private val FLOATING_BAR_RESERVED_HEIGHT = 80.dp

@Composable
fun IzukiNavHost(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showNavBar = currentRoute in BOTTOM_ROUTES

    fun navigate(route: String) {
        if (currentRoute == route) return
        navController.navigate(route) {
            launchSingleTop = true
            restoreState = true
            popUpTo(Routes.RUN) { saveState = true }
        }
    }

    BoxWithConstraints {
        val wide = maxWidth >= WIDE_LAYOUT_MIN_WIDTH
        if (showNavBar && wide) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail {
                    BottomItem.entries.forEach { item ->
                        NavigationRailItem(
                            selected = currentRoute == item.route,
                            onClick = { navigate(item.route) },
                            icon = { Icon(item.icon(), contentDescription = stringResource(item.labelRes)) },
                            label = { Text(stringResource(item.labelRes)) },
                        )
                    }
                }
                NavContent(
                    navController = navController,
                    modifier = Modifier.weight(1f),
                    reserveBottomBar = false,
                )
            }
        } else {
            // 悬浮底栏：作为覆盖层叠在页面之上，显示 / 隐藏只做位移动画，
            // 不占用布局空间，因此页面内容不会被推挤或重排。
            Box(modifier = Modifier.fillMaxSize()) {
                NavContent(
                    navController = navController,
                    modifier = Modifier.fillMaxSize(),
                    reserveBottomBar = true,
                )
                AnimatedVisibility(
                    visible = showNavBar,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically(
                        animationSpec = tween(BAR_ANIMATION_MS),
                        initialOffsetY = { it },
                    ) + fadeIn(tween(BAR_ANIMATION_MS)),
                    exit = slideOutVertically(
                        animationSpec = tween(BAR_ANIMATION_MS),
                        targetOffsetY = { it },
                    ) + fadeOut(tween(BAR_ANIMATION_MS)),
                ) {
                    NavigationBar {
                        BottomItem.entries.forEach { item ->
                            NavigationBarItem(
                                selected = currentRoute == item.route,
                                onClick = { navigate(item.route) },
                                icon = { Icon(item.icon(), contentDescription = stringResource(item.labelRes)) },
                                label = { Text(stringResource(item.labelRes)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavContent(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    reserveBottomBar: Boolean = false,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.RUN,
        modifier = modifier,
    ) {
        composable(Routes.RUN) {
            BottomTabContent(reserveBottomBar) {
                RunScreen(
                    onOpenSetup = { navController.navigate(Routes.SETUP) },
                    onOpenScripts = {
                        navController.navigate(Routes.SCRIPTS) { launchSingleTop = true }
                    },
                )
            }
        }
        composable(Routes.SCRIPTS) {
            BottomTabContent(reserveBottomBar) {
                ScriptsScreen(
                    onOpenScript = { id -> navController.navigate(Routes.editor(id)) },
                    onOpenInspector = { navController.navigate(Routes.INSPECTOR) },
                    onOpenSchedule = { navController.navigate(Routes.SCHEDULE) },
                )
            }
        }
        composable(Routes.LOGS) {
            BottomTabContent(reserveBottomBar) {
                LogsScreen()
            }
        }
        composable(Routes.SETTINGS) {
            BottomTabContent(reserveBottomBar) {
                SettingsScreen(
                    onOpenCategory = { category ->
                        navController.navigate(Routes.settingsDetail(category))
                    },
                    onOpenAi = { navController.navigate(Routes.AI) },
                )
            }
        }
        composable(
            route = Routes.SETTINGS_DETAIL,
            arguments = listOf(
                navArgument(Routes.ARG_SETTINGS_CATEGORY) { type = NavType.StringType },
            ),
        ) { entry ->
            SettingsDetailScreen(
                categoryKey = entry.arguments?.getString(Routes.ARG_SETTINGS_CATEGORY).orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument(Routes.ARG_SCRIPT_ID) { type = NavType.StringType }),
        ) { entry ->
            EditorScreen(
                scriptId = entry.arguments?.getString(Routes.ARG_SCRIPT_ID).orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETUP) {
            PermissionScreen(
                onBack = { navController.popBackStack() },
                onOpenHid = { navController.navigate(Routes.HID) },
            )
        }
        composable(Routes.INSPECTOR) {
            LayoutInspectorScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SCHEDULE) {
            ScheduleScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.HID) {
            HidSetupScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.AI) {
            AiAgentScreen(onBack = { navController.popBackStack() })
        }
    }
}

/**
 * 为显示悬浮底栏的页面在底部预留高度，避免列表最后一项被底栏遮住。
 * 留白只在底栏会出现的页面生效，因此切换页面时不会引起共享容器重排。
 */
@Composable
private fun BottomTabContent(
    reserveBottomBar: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = if (reserveBottomBar) FLOATING_BAR_RESERVED_HEIGHT else 0.dp),
    ) {
        content()
    }
}
