package com.benton.izukijs.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.benton.izukijs.R
import com.benton.izukijs.ui.ai.AiAgentScreen
import com.benton.izukijs.ui.common.LocalSnackbarController
import com.benton.izukijs.ui.common.SnackbarController
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

/** 页面切换动画时长。压短到接近两帧节奏，减少切页时的拖沓感。 */
private const val NAV_ANIM_MS = 180

/**
 * 平级 tab 切换动画时长。比详情页更短：交叉淡入淡出期间新旧两页会同时组合，
 * 压短时长可缩短这段重叠窗口，在保留转场观感的同时降低掉帧概率。
 */
private const val TAB_ANIM_MS = 140

/**
 * 平级 tab 用短交叉淡入淡出：只改透明度、不触发内容重排，是开销最低的转场。
 */
private val TabFadeEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(TAB_ANIM_MS))
}
private val TabFadeExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(TAB_ANIM_MS))
}

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

    // 全局唯一的 Snackbar 宿主：通过 CompositionLocal 提供给所有页面，替代零散的 Toast。
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarController = remember(snackbarHostState) { SnackbarController(snackbarHostState) }

    CompositionLocalProvider(LocalSnackbarController provides snackbarController) {
        BoxWithConstraints {
            val wide = maxWidth >= WIDE_LAYOUT_MIN_WIDTH
            if (wide) {
                // 宽屏：侧边 NavigationRail 参与布局，内容区不再预留底栏高度。
                // 无底栏，Snackbar 直接浮在内容区底部。
                Row(modifier = Modifier.fillMaxSize()) {
                    if (showNavBar) {
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
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        NavContent(
                            navController = navController,
                            modifier = Modifier.fillMaxSize(),
                        )
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                        )
                    }
                }
            } else {
                // 窄屏：使用标准 Scaffold.bottomBar，底栏参与布局与 inset 计算，
                // 内容不会被遮挡，也不再需要硬编码的预留高度。显隐时用位移 + 淡入淡出过渡，
                // 避免进入详情页时底栏生硬地瞬间消失 / 出现。
                // snackbarHost 交给 Scaffold，Snackbar 会自动浮在底栏之上。
                Scaffold(
                    bottomBar = {
                        AnimatedVisibility(
                            visible = showNavBar,
                            enter = slideInVertically(tween(NAV_ANIM_MS)) { it } + fadeIn(tween(NAV_ANIM_MS)),
                            exit = slideOutVertically(tween(NAV_ANIM_MS)) { it } + fadeOut(tween(NAV_ANIM_MS)),
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
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { innerPadding ->
                    NavContent(
                        navController = navController,
                        // padding 把内容抬到底栏之上；consumeWindowInsets 标记已消费的系统栏 inset，
                        // 避免各页面自身的 Scaffold 再次叠加底部 inset。
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
private fun NavContent(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.RUN,
        modifier = modifier,
        // 详情页使用 shared-axis：前进从右侧滑入、返回从左侧滑入，配合淡入淡出。
        enterTransition = {
            fadeIn(tween(NAV_ANIM_MS)) + slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Left,
                tween(NAV_ANIM_MS),
            )
        },
        exitTransition = {
            fadeOut(tween(NAV_ANIM_MS)) + slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Left,
                tween(NAV_ANIM_MS),
            )
        },
        popEnterTransition = {
            fadeIn(tween(NAV_ANIM_MS)) + slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Right,
                tween(NAV_ANIM_MS),
            )
        },
        popExitTransition = {
            fadeOut(tween(NAV_ANIM_MS)) + slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Right,
                tween(NAV_ANIM_MS),
            )
        },
    ) {
        composable(
            route = Routes.RUN,
            enterTransition = TabFadeEnter,
            exitTransition = TabFadeExit,
            popEnterTransition = TabFadeEnter,
            popExitTransition = TabFadeExit,
        ) {
            RunScreen(
                onOpenSetup = { navController.navigate(Routes.SETUP) },
                onOpenScripts = {
                    navController.navigate(Routes.SCRIPTS) { launchSingleTop = true }
                },
            )
        }
        composable(
            route = Routes.SCRIPTS,
            enterTransition = TabFadeEnter,
            exitTransition = TabFadeExit,
            popEnterTransition = TabFadeEnter,
            popExitTransition = TabFadeExit,
        ) {
            ScriptsScreen(
                onOpenScript = { id -> navController.navigate(Routes.editor(id)) },
                onOpenInspector = { navController.navigate(Routes.INSPECTOR) },
                onOpenSchedule = { navController.navigate(Routes.SCHEDULE) },
            )
        }
        composable(
            route = Routes.LOGS,
            enterTransition = TabFadeEnter,
            exitTransition = TabFadeExit,
            popEnterTransition = TabFadeEnter,
            popExitTransition = TabFadeExit,
        ) {
            LogsScreen()
        }
        composable(
            route = Routes.SETTINGS,
            enterTransition = TabFadeEnter,
            exitTransition = TabFadeExit,
            popEnterTransition = TabFadeEnter,
            popExitTransition = TabFadeExit,
        ) {
            SettingsScreen(
                onOpenCategory = { category ->
                    navController.navigate(Routes.settingsDetail(category))
                },
                onOpenAi = { navController.navigate(Routes.AI) },
            )
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
