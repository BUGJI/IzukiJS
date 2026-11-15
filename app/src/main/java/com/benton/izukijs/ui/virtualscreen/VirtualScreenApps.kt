package com.benton.izukijs.ui.virtualscreen

/** 可启动到虚拟屏的应用条目。 */
data class VirtualScreenApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean = false,
)

/**
 * 按名称或包名过滤应用列表。
 *
 * 查询为空时返回全部；[limit] 用于底部弹层限制一次性渲染数量。
 */
fun filterVirtualScreenApps(
    apps: List<VirtualScreenApp>,
    query: String,
    limit: Int = Int.MAX_VALUE,
): List<VirtualScreenApp> {
    val trimmed = query.trim()
    val matched = if (trimmed.isEmpty()) {
        apps
    } else {
        apps.filter { app ->
            app.label.contains(trimmed, ignoreCase = true) ||
                app.packageName.contains(trimmed, ignoreCase = true)
        }
    }
    return if (matched.size > limit) matched.take(limit) else matched
}
