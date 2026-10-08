package com.benton.izukijs.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 全局提示控制器。以 [SnackbarHostState] 为后端，供各页面在协程中弹出提示，替代零散的 Toast。
 * 带操作按钮时，[show] 会挂起直到提示消失，并返回用户是否点击了操作（用于「撤销」等）。
 */
class SnackbarController(private val hostState: SnackbarHostState) {

    suspend fun show(
        message: String,
        actionLabel: String? = null,
        withDismissAction: Boolean = false,
        duration: SnackbarDuration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long,
    ): SnackbarResult = hostState.showSnackbar(
        message = message,
        actionLabel = actionLabel,
        withDismissAction = withDismissAction,
        duration = duration,
    )
}

val LocalSnackbarController = staticCompositionLocalOf<SnackbarController> {
    error("SnackbarController 未提供，请确认已在 IzukiNavHost 中注入")
}
