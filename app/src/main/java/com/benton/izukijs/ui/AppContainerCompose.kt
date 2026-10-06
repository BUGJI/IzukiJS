package com.benton.izukijs.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.benton.izukijs.di.AppContainer
import com.benton.izukijs.di.appContainer

@Composable
fun rememberAppContainer(): AppContainer {
    val context = LocalContext.current
    return remember(context) { context.appContainer }
}
