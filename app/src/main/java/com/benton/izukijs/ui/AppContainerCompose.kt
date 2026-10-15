package com.benton.izukijs.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.benton.izukijs.di.AppContainer
import com.benton.izukijs.di.appContainer

@Composable
fun rememberAppContainer(): AppContainer {
    // 用 applicationContext 作 key：配置变更 / recreate 时它保持稳定，避免重复查找。
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) { appContext.appContainer }
}
