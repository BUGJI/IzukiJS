package com.benton.izukijs.controller.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 控件动作的通用辅助：向上寻找真正可点击 / 可滚动 / 可编辑的祖先。
 *
 * 多行列表、卡片和按钮内部常把文字放在不可点击的子节点上；只对命中节点自身执行动作
 * 会失败或点偏。这里统一向上攀爬，返回最深的匹配祖先，调用方负责 [releaseNode]。
 */
internal fun AccessibilityNodeInfo.findActionableAncestor(
    maxDepth: Int = DEFAULT_MAX_ANCESTOR_DEPTH,
    predicate: (AccessibilityNodeInfo) -> Boolean,
): AccessibilityNodeInfo? {
    var current = parent
    var best: AccessibilityNodeInfo? = null
    var depth = 0
    while (current != null && depth < maxDepth) {
        val next = current.parent
        if (predicate(current)) {
            best?.releaseNode()
            best = current
        } else {
            current.releaseNode()
        }
        current = next
        depth++
    }
    current?.releaseNode()
    return best
}

/** 向上寻找面积最大的可滚动祖先，避免嵌套滚动容器里选到外层。 */
internal fun AccessibilityNodeInfo.findLargestScrollableAncestor(
    maxDepth: Int = DEFAULT_MAX_ANCESTOR_DEPTH,
): AccessibilityNodeInfo? {
    var current = parent
    var best: AccessibilityNodeInfo? = null
    var bestArea = -1
    var depth = 0
    while (current != null && depth < maxDepth) {
        val next = current.parent
        if (current.isScrollable) {
            val area = current.boundsArea()
            if (area > bestArea) {
                best?.releaseNode()
                best = current
                bestArea = area
            } else {
                current.releaseNode()
            }
        } else {
            current.releaseNode()
        }
        current = next
        depth++
    }
    current?.releaseNode()
    return best
}

internal fun AccessibilityNodeInfo.boundsArea(): Int {
    val rect = Rect()
    getBoundsInScreen(rect)
    return (rect.width().coerceAtLeast(0)).let { width ->
        width * rect.height().coerceAtLeast(0)
    }
}

internal const val DEFAULT_MAX_ANCESTOR_DEPTH = 20
