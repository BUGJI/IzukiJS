package com.benton.izukijs.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
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
import com.benton.izukijs.ui.mcp.McpServerScreen
import com.benton.izukijs.ui.permission.PermissionScreen
import com.benton.izukijs.ui.run.RunScreen
import com.benton.izukijs.ui.schedule.ScheduleScreen
import com.benton.izukijs.ui.scripts.ScriptsScreen
import com.benton.izukijs.ui.settings.SettingsCategory
import com.benton.izukijs.ui.settings.SettingsDetailScreen
import com.benton.izukijs.ui.settings.SettingsScreen
import com.benton.izukijs.ui.update.UpdatePromptHost
import kotlinx.coroutines.launch

object Routes {
    const val ARG_SCRIPT_ID = "scriptId"
    const val ARG_SETTINGS_CATEGORY = "category"

    /** 主界面占位路由：四个底部标签由 [HorizontalPager] 承载，此路由只作为详情页的返回落点。 */
    const val MAIN = "main"

    const val SETTINGS_DETAIL = "settings/{$ARG_SETTINGS_CATEGORY}"
    const val EDITOR = "editor/{$ARG_SCRIPT_ID}"
    const val SETUP = "setup"
    const val INSPECTOR = "inspector"
    const val SCHEDULE = "schedule"
    const val HID = "hid"
    const val AI = "ai"
    const val MCP = "mcp"

    fun editor(scriptId: String) = "editor/$scriptId"

    fun settingsDetail(category: SettingsCategory) = "settings/${category.key}"
}

/** 底部导航的四个主标签，顺序即 [HorizontalPager] 的页序。 */
private enum class BottomItem(
    @StringRes val labelRes: Int,
) {
    RUN(R.string.nav_run),
    SCRIPTS(R.string.nav_scripts),
    LOGS(R.string.nav_logs),
    SETTINGS(R.string.nav_settings),
}

/** 底部导航图标；日志使用专用矢量图，避免与「菜单」图标语义混淆。 */
@Composable
private fun BottomItem.icon(): ImageVector = when (this) {
    BottomItem.RUN -> Icons.Filled.PlayArrow
    BottomItem.SCRIPTS -> Icons.AutoMirrored.Filled.List
    BottomItem.LOGS -> ImageVector.vectorResource(R.drawable.ic_nav_logs)
    BottomItem.SETTINGS -> Icons.Filled.Settings
}

/** 宽屏判定阈值：>= 600dp 时改用侧边 NavigationRail。 */
private val WIDE_LAYOUT_MIN_WIDTH = 600.dp

/** 详情页切换动画时长。压短到接近两帧节奏，减少切页时的拖沓感。 */
private const val NAV_ANIM_MS = 180

@Composable
fun IzukiNavHost(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // 仅当停留在主界面（未打开任何详情页）时展示底部标签。
    val showNavBar = currentRoute == Routes.MAIN

    // 页序状态提升到导航层之外，切换详情页时保持不变。
    val pagerState = rememberPagerState(pageCount = { BottomItem.entries.size })
    val scope = rememberCoroutineScope()

    fun selectTab(index: Int) {
        if (pagerState.currentPage == index) return
        scope.launch { pagerState.animateScrollToPage(index) }
    }

    // 全局唯一的 Snackbar 宿主：通过 CompositionLocal 提供给所有页面，替代零散的 Toast。
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarController = remember(snackbarHostState) { SnackbarController(snackbarHostState) }

    CompositionLocalProvider(LocalSnackbarController provides snackbarController) {
        UpdatePromptHost()
        BoxWithConstraints {
            val wide = maxWidth >= WIDE_LAYOUT_MIN_WIDTH
            if (wide) {
                // 宽屏：侧边 NavigationRail 参与布局，内容区不再预留底栏高度。
                // 无底栏，Snackbar 直接浮在内容区底部。
                Row(modifier = Modifier.fillMaxSize()) {
                    // 详情页显隐时用水平展开 / 收起过渡，避免 Rail 突然消失导致内容宽度跳变。
                    AnimatedVisibility(
                        visible = showNavBar,
                        enter = fadeIn(tween(NAV_ANIM_MS)) + expandHorizontally(tween(NAV_ANIM_MS)),
                        exit = fadeOut(tween(NAV_ANIM_MS)) + shrinkHorizontally(tween(NAV_ANIM_MS)),
                    ) {
                        NavigationRail {
                            BottomItem.entries.forEachIndexed { index, item ->
                                NavigationRailItem(
                                    selected = pagerState.currentPage == index,
                                    onClick = { selectTab(index) },
                                    icon = { Icon(item.icon(), contentDescription = stringResource(item.labelRes)) },
                                    label = { Text(stringResource(item.labelRes)) },
                                )
                            }
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        MainTabsHost(
                            pagerState = pagerState,
                            navController = navController,
                            onSelectTab = ::selectTab,
                            // 有详情页覆盖时禁用 pager 滑动，避免横滑穿透到底层标签。
                            userScrollEnabled = showNavBar,
                        )
                        // 详情页覆盖在主界面之上，主界面 pager 保持组合以保留各标签状态。
                        DetailNavHost(
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
                                BottomItem.entries.forEachIndexed { index, item ->
                                    NavigationBarItem(
                                        selected = pagerState.currentPage == index,
                                        onClick = { selectTab(index) },
                                        icon = { Icon(item.icon(), contentDescription = stringResource(item.labelRes)) },
                                        label = { Text(stringResource(item.labelRes)) },
                                    )
                                }
                            }
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding),
                    ) {
                        MainTabsHost(
                            pagerState = pagerState,
                            navController = navController,
                            onSelectTab = ::selectTab,
                            // 有详情页覆盖时禁用 pager 滑动，避免横滑穿透到底层标签。
                            userScrollEnabled = showNavBar,
                        )
                        DetailNavHost(
                            navController = navController,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/** 把主界面各标签的跳转回调接到导航控制器上。 */
@Composable
private fun MainTabsHost(
    pagerState: PagerState,
    navController: NavHostController,
    onSelectTab: (Int) -> Unit,
    userScrollEnabled: Boolean,
) {
    // 统一用 launchSingleTop 打开详情页，避免快速连点把同一页压栈多次。
    fun open(route: String) = navController.navigate(route) { launchSingleTop = true }
    MainTabs(
        pagerState = pagerState,
        onOpenSetup = { open(Routes.SETUP) },
        onOpenScripts = { onSelectTab(BottomItem.SCRIPTS.ordinal) },
        onOpenScript = { id -> open(Routes.editor(id)) },
        onOpenInspector = { open(Routes.INSPECTOR) },
        onOpenSchedule = { open(Routes.SCHEDULE) },
        onOpenCategory = { category -> open(Routes.settingsDetail(category)) },
        onOpenAi = { open(Routes.AI) },
        onOpenMcp = { open(Routes.MCP) },
        userScrollEnabled = userScrollEnabled,
    )
}

/**
 * 四个底部标签的可滑动容器。用 [HorizontalPager] 承载，左右滑动或点击底栏即可切换；
 * `beyondViewportPageCount` 设为标签总数，让四个页面常驻组合，避免切走后状态丢失。
 */
@Composable
private fun MainTabs(
    pagerState: PagerState,
    onOpenSetup: () -> Unit,
    onOpenScripts: () -> Unit,
    onOpenScript: (String) -> Unit,
    onOpenInspector: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenCategory: (SettingsCategory) -> Unit,
    onOpenAi: () -> Unit,
    onOpenMcp: () -> Unit,
    userScrollEnabled: Boolean,
) {
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = BottomItem.entries.size,
        userScrollEnabled = userScrollEnabled,
    ) { page ->
        when (BottomItem.entries[page]) {
            BottomItem.RUN -> RunScreen(
                onOpenSetup = onOpenSetup,
                onOpenScripts = onOpenScripts,
            )

            BottomItem.SCRIPTS -> ScriptsScreen(
                onOpenScript = onOpenScript,
                onOpenInspector = onOpenInspector,
                onOpenSchedule = onOpenSchedule,
            )

            BottomItem.LOGS -> LogsScreen()

            BottomItem.SETTINGS -> SettingsScreen(
                onOpenCategory = onOpenCategory,
                onOpenAi = onOpenAi,
                onOpenMcp = onOpenMcp,
            )
        }
    }
}

/**
 * 详情页导航图。起始页为透明的 [Routes.MAIN] 占位，主界面 pager 常驻其下方；
 * 打开详情时新页面沿共享轴滑入覆盖，返回时回到占位页即露出主界面。
 */
@Composable
private fun DetailNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.MAIN,
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
        composable(Routes.MAIN) {
            // 透明占位：保持主界面 pager 可见 / 可交互。
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
                onOpenHid = { navController.navigate(Routes.HID) { launchSingleTop = true } },
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
        composable(Routes.MCP) {
            McpServerScreen(onBack = { navController.popBackStack() })
        }
    }
}
