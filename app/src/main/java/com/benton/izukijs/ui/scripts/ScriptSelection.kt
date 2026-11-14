package com.benton.izukijs.ui.scripts

import com.benton.izukijs.model.ScriptInfo

/**
 * 脚本列表多选的纯逻辑，便于 JVM 单测。
 *
 * 选中项以脚本 id 为键：标签分组下同一脚本会出现在多个组里，
 * 用 id 去重可保证勾选状态在组间一致。
 */

internal fun toggleSelection(selected: Set<String>, id: String): Set<String> =
    if (id in selected) selected - id else selected + id

/** 丢弃已不存在的脚本 id，避免外部删除后留下幽灵选中项。 */
internal fun pruneSelection(selected: Set<String>, scripts: Collection<ScriptInfo>): Set<String> {
    if (selected.isEmpty()) return selected
    val existing = scripts.mapTo(HashSet()) { it.id }
    return selected.filterTo(LinkedHashSet()) { it in existing }
}

/**
 * 全选 / 取消全选，只作用于当前可见集合；不可见但已选中的项保持不变。
 */
internal fun toggleSelectAllVisible(
    selected: Set<String>,
    visible: Collection<ScriptInfo>,
): Set<String> {
    val visibleIds = visible.mapTo(LinkedHashSet()) { it.id }
    if (visibleIds.isEmpty()) return selected
    return if (visibleIds.all { it in selected }) selected - visibleIds else selected + visibleIds
}
