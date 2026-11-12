package com.benton.izukijs.controller.record

import com.benton.izukijs.controller.NodeSnapshot
import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.core.log.LogBus

/**
 * 用户动作的语义上下文：前台包名 + 命中控件。
 *
 * 控件树 dump 较慢，按 [TREE_TTL_MS] 缓存；一次 poll 批次内只刷新一次，
 * 避免连续点击时反复 uiautomator dump。
 */
class RecordingContextResolver(
    private val nodeTreeProvider: (timeoutMs: Long) -> NodeTreeResult,
    private val foregroundProvider: () -> String?,
    private val logBus: LogBus,
) {

    private var cachedRoot: NodeSnapshot? = null
    private var cachedAt = 0L
    private var lastForeground: String? = null

    fun foreground(): String = runCatching { foregroundProvider() }.getOrNull().orEmpty()

    /** 返回前台包名变化事件；没有变化返回 null。 */
    fun foregroundChanged(packageName: String): String? {
        val previous = lastForeground
        if (packageName.isBlank()) return null
        if (previous == null) {
            lastForeground = packageName
            return null
        }
        if (previous != packageName) {
            lastForeground = packageName
            return previous
        }
        return null
    }

    fun refreshIfStale(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && cachedRoot != null && now - cachedAt < TREE_TTL_MS) return
        cachedAt = now
        val result = runCatching { nodeTreeProvider(TREE_TIMEOUT_MS) }.getOrNull()
        cachedRoot = (result as? NodeTreeResult.Success)?.root
        if (result is NodeTreeResult.Failure) {
            logBus.debug("用户录制读取控件树失败: ${result.message}")
        }
    }

    fun targetsAt(x: Int, y: Int): List<UiTarget> {
        val root = cachedRoot ?: return emptyList()
        val matches = ArrayList<NodeSnapshot>()
        collect(root, x, y, matches)
        if (matches.isEmpty()) return emptyList()
        // 从最内层向外取，最多 3 层：叶子节点通常才是用户真正点的控件。
        return matches.asReversed()
            .asSequence()
            .filter { it.right > it.left && it.bottom > it.top }
            .take(MAX_TARGETS)
            .map { it.toUiTarget() }
            .toList()
    }

    private fun collect(node: NodeSnapshot, x: Int, y: Int, out: MutableList<NodeSnapshot>) {
        if (x < node.left || x > node.right || y < node.top || y > node.bottom) return
        out.add(node)
        node.children.forEach { collect(it, x, y, out) }
    }

    private fun NodeSnapshot.toUiTarget(): UiTarget = UiTarget(
        className = className,
        text = text,
        viewId = viewId,
        contentDescription = contentDescription,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        clickable = clickable,
        editable = editable,
    )

    private companion object {
        const val TREE_TTL_MS = 700L
        const val TREE_TIMEOUT_MS = 800L
        const val MAX_TARGETS = 3
    }
}
