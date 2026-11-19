package com.benton.izukijs.controller.accessibility

/**
 * 控件文本 / 描述的条件匹配：精确、包含、正则三者同时给出时取交集。
 * 抽成纯函数，便于脱离 Android 无障碍框架做单元测试。
 */
internal fun matchesNodeValue(
    value: String?,
    exact: String?,
    contains: String?,
    regex: Regex?,
    startsWith: String? = null,
    endsWith: String? = null,
): Boolean {
    if (exact != null && value != exact) return false
    if (contains != null && (value == null || !value.contains(contains))) return false
    if (startsWith != null && (value == null || !value.startsWith(startsWith))) return false
    if (endsWith != null && (value == null || !value.endsWith(endsWith))) return false
    if (regex != null && (value == null || !regex.containsMatchIn(value))) return false
    return true
}
