package com.benton.izukijs.core.notification

/**
 * 通知点击后的落点常量。
 *
 * 放在 `core.notification`，让通知层不必反向依赖 `ui.navigation.Routes`；
 * `Routes` 复用同一组值，避免两边字面量漂移。
 */
object NotificationRoutes {
    const val LOGS = "tab:logs"
    const val MCP = "mcp"
}
