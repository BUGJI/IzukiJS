package com.benton.izukijs.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.benton.izukijs.ui.permission.SetupWizardScreen
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

private enum class BottomItem(val route: String, val label: String, val icon: ImageVector) {
    RUN(Routes.RUN, "运行", Icons.Filled.PlayArrow),
    SCRIPTS(Routes.SCRIPTS, "脚本", Icons.AutoMirrored.Filled.List),
    SETTINGS(Routes.SETTINGS, "设置", Icons.Filled.Settings),
}

private val BOTTOM_ROUTES = BottomItem.entries.map { it.route }.toSet()

@Composable
fun IzukiNavHost(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in BOTTOM_ROUTES

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    BottomItem.entries.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                if (currentRoute != item.route) {
                                    navController.navigate(item.route) {
                                        launchSingleTop = true
                                        restoreState = true
                                        popUpTo(Routes.RUN) { saveState = true }
                                    }
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.RUN,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.RUN) {
                RunScreen(
                    onOpenSetup = { navController.navigate(Routes.SETUP) },
                    onOpenScripts = {
                        navController.navigate(Routes.SCRIPTS) { launchSingleTop = true }
                    },
                )
            }
            composable(Routes.SCRIPTS) {
                ScriptsScreen(
                    onOpenScript = { id -> navController.navigate(Routes.editor(id)) },
                    onOpenInspector = { navController.navigate(Routes.INSPECTOR) },
                    onOpenSchedule = { navController.navigate(Routes.SCHEDULE) },
                )
            }
            composable(Routes.SETTINGS) {
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
                SetupWizardScreen(
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
}
