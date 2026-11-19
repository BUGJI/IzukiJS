package com.benton.izukijs.controller.accessibility

import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.benton.izukijs.controller.DeviceController
import com.benton.izukijs.controller.GestureStroke
import com.benton.izukijs.controller.NodeBounds
import com.benton.izukijs.controller.NodeSnapshot
import com.benton.izukijs.developer.DeveloperTuning
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode

/**
 * 无障碍模式的控制实现。能力：手势、文本、控件树，以及（API30+）截图。
 */
class AccessibilityController(
    private val serviceProvider: () -> AccessibilityServiceHandle?,
) : DeviceController {

    override val mode: ControlMode = ControlMode.ACCESSIBILITY

    private val service: AccessibilityServiceHandle? get() = serviceProvider()

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
        service?.tapAt(x, y, DeveloperTuning.current.accessibilityTapDurationMs.toLong()) ?: false

    override fun longClick(x: Float, y: Float, durationMs: Long): Boolean =
        service?.tapAt(x, y, durationMs) ?: false

    override fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
    ): Boolean = service?.swipeFromTo(x1, y1, x2, y2, durationMs) ?: false

    override fun supportsGesture(): Boolean = true

    override fun gesture(strokes: List<GestureStroke>): Boolean =
        service?.performStrokes(strokes) ?: false

    override fun inputText(text: String): Boolean = service?.inputText(text) ?: false

    override fun hasEditableFocus(): Boolean? = service?.hasEditableFocus()

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
            root.toSnapshot(depth = 0, indexInParent = 0)
        } finally {
            root.releaseNode()
        }
    }

    /** 当前可见窗口；调用方负责回收各窗口的 root 节点。 */
    fun windowList(): List<AccessibilityWindowInfo> = service?.windowList() ?: emptyList()

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

    private fun AccessibilityNodeInfo.toSnapshot(depth: Int, indexInParent: Int): NodeSnapshot {
        val rect = android.graphics.Rect()
        getBoundsInScreen(rect)
        val children = if (depth < MAX_DEPTH) {
            (0 until childCount).mapNotNull { i ->
                val child = getChild(i) ?: return@mapNotNull null
                try {
                    child.toSnapshot(depth + 1, i)
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
            packageName = packageName?.toString(),
            scrollable = isScrollable,
            enabled = isEnabled,
            visible = isVisibleToUser,
            indexInParent = indexInParent,
            depth = depth,
            checkable = isCheckable,
            checked = isChecked,
            focusable = isFocusable,
            focused = isFocused,
            selected = isSelected,
            longClickable = isLongClickable,
            password = isPassword,
        )
    }

    data class NodeCriteria(
        val viewId: String? = null,
        val viewIdContains: String? = null,
        val viewIdStartsWith: String? = null,
        val viewIdEndsWith: String? = null,
        val viewIdRegex: String? = null,
        val text: String? = null,
        val textContains: String? = null,
        val textStartsWith: String? = null,
        val textEndsWith: String? = null,
        val textRegex: String? = null,
        val desc: String? = null,
        val descContains: String? = null,
        val descStartsWith: String? = null,
        val descEndsWith: String? = null,
        val descRegex: String? = null,
        val className: String? = null,
        val classNameContains: String? = null,
        val classNameStartsWith: String? = null,
        val classNameEndsWith: String? = null,
        val classNameRegex: String? = null,
        val clickable: Boolean? = null,
        val longClickable: Boolean? = null,
        val editable: Boolean? = null,
        val scrollable: Boolean? = null,
        val enabled: Boolean? = null,
        val visible: Boolean? = null,
        val checkable: Boolean? = null,
        val checked: Boolean? = null,
        val focusable: Boolean? = null,
        val focused: Boolean? = null,
        val selected: Boolean? = null,
        val password: Boolean? = null,
        val packageName: String? = null,
        val packageNameContains: String? = null,
        val packageNameStartsWith: String? = null,
        val packageNameEndsWith: String? = null,
        val packageNameRegex: String? = null,
        val bounds: NodeBounds? = null,
        val boundsInside: NodeBounds? = null,
        val boundsContains: NodeBounds? = null,
    ) {
        private val viewIdPattern = viewIdRegex?.let { Regex(it) }
        private val textPattern = textRegex?.let { Regex(it) }
        private val descPattern = descRegex?.let { Regex(it) }
        private val classNamePattern = classNameRegex?.let { Regex(it) }
        private val packageNamePattern = packageNameRegex?.let { Regex(it) }

        fun matches(node: AccessibilityNodeInfo): Boolean {
            if (!matchesNodeValue(
                    node.viewIdResourceName,
                    viewId,
                    viewIdContains,
                    viewIdPattern,
                    viewIdStartsWith,
                    viewIdEndsWith,
                )
            ) {
                return false
            }
            if (!matchesNodeValue(
                    node.text?.toString(),
                    text,
                    textContains,
                    textPattern,
                    textStartsWith,
                    textEndsWith,
                )
            ) {
                return false
            }
            val description = node.contentDescription?.toString()
            if (!matchesNodeValue(
                    description,
                    desc,
                    descContains,
                    descPattern,
                    descStartsWith,
                    descEndsWith,
                )
            ) {
                return false
            }
            val nodeClass = node.className?.toString()
            if (!matchesNodeValue(
                    nodeClass,
                    className,
                    classNameContains,
                    classNamePattern,
                    classNameStartsWith,
                    classNameEndsWith,
                )
            ) {
                return false
            }
            val nodePackage = node.packageName?.toString()
            if (!matchesNodeValue(
                    nodePackage,
                    packageName,
                    packageNameContains,
                    packageNamePattern,
                    packageNameStartsWith,
                    packageNameEndsWith,
                )
            ) {
                return false
            }
            if (clickable != null && node.isClickable != clickable) return false
            if (longClickable != null && node.isLongClickable != longClickable) return false
            if (editable != null && node.isEditable != editable) return false
            if (scrollable != null && node.isScrollable != scrollable) return false
            if (enabled != null && node.isEnabled != enabled) return false
            if (visible != null && node.isVisibleToUser != visible) return false
            if (checkable != null && node.isCheckable != checkable) return false
            if (checked != null && node.isChecked != checked) return false
            if (focusable != null && node.isFocusable != focusable) return false
            if (focused != null && node.isFocused != focused) return false
            if (selected != null && node.isSelected != selected) return false
            if (password != null && node.isPassword != password) return false
            if (bounds != null || boundsInside != null || boundsContains != null) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val actual = NodeBounds(rect.left, rect.top, rect.right, rect.bottom)
                if (bounds != null && actual != bounds) return false
                if (boundsInside != null && !actual.inside(boundsInside)) return false
                if (boundsContains != null && !actual.contains(boundsContains)) return false
            }
            return true
        }

        /** 纯快照匹配，用于 explain / codegen 与 JVM 测试。 */
        fun matchesSnapshot(node: NodeSnapshot): Boolean {
            if (!matchesNodeValue(
                    node.viewId,
                    viewId,
                    viewIdContains,
                    viewIdPattern,
                    viewIdStartsWith,
                    viewIdEndsWith,
                )
            ) {
                return false
            }
            if (!matchesNodeValue(
                    node.text,
                    text,
                    textContains,
                    textPattern,
                    textStartsWith,
                    textEndsWith,
                )
            ) {
                return false
            }
            if (!matchesNodeValue(
                    node.contentDescription,
                    desc,
                    descContains,
                    descPattern,
                    descStartsWith,
                    descEndsWith,
                )
            ) {
                return false
            }
            if (!matchesNodeValue(
                    node.className,
                    className,
                    classNameContains,
                    classNamePattern,
                    classNameStartsWith,
                    classNameEndsWith,
                )
            ) {
                return false
            }
            if (!matchesNodeValue(
                    node.packageName,
                    packageName,
                    packageNameContains,
                    packageNamePattern,
                    packageNameStartsWith,
                    packageNameEndsWith,
                )
            ) {
                return false
            }
            if (clickable != null && node.clickable != clickable) return false
            if (longClickable != null && node.longClickable != longClickable) return false
            if (editable != null && node.editable != editable) return false
            if (scrollable != null && node.scrollable != scrollable) return false
            if (enabled != null && node.enabled != enabled) return false
            if (visible != null && node.visible != visible) return false
            if (checkable != null && node.checkable != checkable) return false
            if (checked != null && node.checked != checked) return false
            if (focusable != null && node.focusable != focusable) return false
            if (focused != null && node.focused != focused) return false
            if (selected != null && node.selected != selected) return false
            if (password != null && node.password != password) return false
            if (bounds != null || boundsInside != null || boundsContains != null) {
                val actual = NodeBounds(node.left, node.top, node.right, node.bottom)
                if (bounds != null && actual != bounds) return false
                if (boundsInside != null && !actual.inside(boundsInside)) return false
                if (boundsContains != null && !actual.contains(boundsContains)) return false
            }
            return true
        }
    }

    companion object {
        private const val MAX_DEPTH = 24

        const val GLOBAL_BACK = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
        const val GLOBAL_HOME = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
        const val GLOBAL_RECENTS =
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
        const val GLOBAL_NOTIFICATIONS =
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        const val GLOBAL_QUICK_SETTINGS =
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        const val GLOBAL_POWER_DIALOG =
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
        const val GLOBAL_SPLIT_SCREEN =
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
        const val GLOBAL_LOCK_SCREEN =
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        const val GLOBAL_DISMISS_NOTIFICATION_SHADE =
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
    }
}

/** 回收无障碍节点。API 33+ 虽标记废弃，但仍是释放节点池引用的推荐做法。 */
@Suppress("DEPRECATION")
internal fun AccessibilityNodeInfo.releaseNode() {
    recycle()
}
