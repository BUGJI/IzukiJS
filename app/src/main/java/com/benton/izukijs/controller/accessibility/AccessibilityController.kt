package com.benton.izukijs.controller.accessibility

import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.benton.izukijs.controller.DeviceController
import com.benton.izukijs.controller.GestureStroke
import com.benton.izukijs.controller.NodeSnapshot
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.service.IzukiAccessibilityService

/**
 * 无障碍模式的控制实现。能力：手势、文本、控件树，以及（API30+）截图。
 */
class AccessibilityController(
    private val serviceProvider: () -> IzukiAccessibilityService?,
) : DeviceController {

    override val mode: ControlMode = ControlMode.ACCESSIBILITY

    private val service: IzukiAccessibilityService? get() = serviceProvider()

    override fun isReady(): Boolean = service != null

    private val cachedCapabilities: Set<Capability> = buildSet {
        add(Capability.GESTURE)
        add(Capability.TEXT)
        add(Capability.NODE_TREE)
        add(Capability.GLOBAL_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(Capability.SCREENSHOT)
        }
    }

    override fun capabilities(): Set<Capability> = cachedCapabilities

    override fun click(x: Float, y: Float): Boolean =
        service?.tapAt(x, y, TAP_DURATION_MS) ?: false

    override fun longClick(x: Float, y: Float, durationMs: Long): Boolean =
        service?.tapAt(x, y, durationMs) ?: false

    override fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
    ): Boolean = service?.swipeFromTo(x1, y1, x2, y2, durationMs) ?: false

    override fun gesture(strokes: List<GestureStroke>): Boolean =
        service?.performStrokes(strokes) ?: false

    override fun inputText(text: String): Boolean = service?.inputText(text) ?: false

    override fun pressKey(keyCode: Int): Boolean {
        val svc = service ?: return false
        val globalAction = when (keyCode) {
            KeyEvent.KEYCODE_BACK -> GLOBAL_BACK
            KeyEvent.KEYCODE_HOME -> GLOBAL_HOME
            KeyEvent.KEYCODE_APP_SWITCH -> GLOBAL_RECENTS
            else -> return false
        }
        return svc.performGlobalAction(globalAction)
    }

    override fun globalAction(action: Int): Boolean = service?.performGlobalAction(action) ?: false

    override fun screenshot() = service?.screenshotBitmap()

    override fun nodeTree(): NodeSnapshot? {
        val root = service?.rootNode() ?: return null
        return try {
            root.toSnapshot(depth = 0)
        } finally {
            root.releaseNode()
        }
    }

    // ---- 控件查找（供选择器 API 使用） ----

    /**
     * 命中第 [index] 个匹配即返回，未命中的节点会被立即回收，避免无障碍节点泄漏。
     * 调用方必须在用完后回收返回的节点。
     */
    fun findFirst(criteria: NodeCriteria, index: Int): AccessibilityNodeInfo? {
        if (index < 0) return null
        val root = service?.rootNode() ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var matchCount = 0
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val matched = criteria.matches(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            if (matched && matchCount == index) {
                queue.forEach { it.releaseNode() }
                return node
            }
            if (matched) matchCount++
            node.releaseNode()
        }
        return null
    }

    /** 返回全部匹配节点，调用方负责回收。 */
    fun findAll(criteria: NodeCriteria): List<AccessibilityNodeInfo> {
        val root = service?.rootNode() ?: return emptyList()
        val result = ArrayList<AccessibilityNodeInfo>(16)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val matched = criteria.matches(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            if (matched) result.add(node) else node.releaseNode()
        }
        return result
    }

    private fun AccessibilityNodeInfo.toSnapshot(depth: Int): NodeSnapshot {
        val rect = android.graphics.Rect()
        getBoundsInScreen(rect)
        val children = if (depth < MAX_DEPTH) {
            (0 until childCount).mapNotNull { i ->
                val child = getChild(i) ?: return@mapNotNull null
                try {
                    child.toSnapshot(depth + 1)
                } finally {
                    child.releaseNode()
                }
            }
        } else {
            emptyList()
        }
        return NodeSnapshot(
            className = className?.toString(),
            text = text?.toString(),
            viewId = viewIdResourceName,
            contentDescription = contentDescription?.toString(),
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            clickable = isClickable,
            editable = isEditable,
            children = children,
        )
    }

    data class NodeCriteria(
        val viewId: String? = null,
        val text: String? = null,
        val desc: String? = null,
        val className: String? = null,
    ) {
        fun matches(node: AccessibilityNodeInfo): Boolean {
            if (viewId != null && node.viewIdResourceName != viewId) return false
            if (text != null && node.text?.toString() != text) return false
            if (desc != null && node.contentDescription?.toString() != desc) return false
            if (className != null && node.className?.toString() != className) return false
            return true
        }
    }

    companion object {
        private const val TAP_DURATION_MS = 50L
        private const val MAX_DEPTH = 24

        const val GLOBAL_BACK = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
        const val GLOBAL_HOME = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
        const val GLOBAL_RECENTS =
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
        const val GLOBAL_NOTIFICATIONS =
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
    }
}

/** 回收无障碍节点。API 33+ 虽标记废弃，但仍是释放节点池引用的推荐做法。 */
@Suppress("DEPRECATION")
internal fun AccessibilityNodeInfo.releaseNode() {
    recycle()
}
