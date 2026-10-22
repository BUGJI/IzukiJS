package com.benton.izukijs.runtime.api

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.JavascriptInterface
import com.benton.izukijs.controller.accessibility.AccessibilityController
import com.benton.izukijs.controller.accessibility.releaseNode
import com.benton.izukijs.runtime.LogBus
import org.json.JSONArray
import org.json.JSONObject

/**
 * 控件选择 API。挂在全局 `selector` 命名空间。
 *
 * 出于稳定性考虑，这里全部返回原始类型（Boolean/String/Int），避免 JS 与 Java 对象互转。
 * 每个从无障碍服务获取的节点都会在返回前回收，防止节点池泄漏。
 * 控件点击 / 长按 / 设值会以 DEBUG 级别写入 [logBus]。
 *
 * 新接口统一接收 JSON 形式的查询条件（见 [parseCriteria]），由 JS 预置脚本包装成对象：
 * `selector.find({ text: "登录" })`、`selector.findAll({ textContains: "登" })` 等。
 */
class SelectorApi(
    private val logBus: LogBus,
    private val controllerProvider: () -> AccessibilityController?,
    private val isExitRequested: () -> Boolean = { false },
) {

    // ---- 旧接口（保持兼容） ----

    @JavascriptInterface
    fun existsById(viewId: String): Boolean = findById(viewId).exists()

    @JavascriptInterface
    fun existsByText(text: String): Boolean = findByText(text).exists()

    @JavascriptInterface
    fun existsByDesc(desc: String): Boolean = findByDesc(desc).exists()

    @JavascriptInterface
    fun countByText(text: String): Int {
        val nodes = controller()?.findAll(AccessibilityController.NodeCriteria(text = text)) ?: return 0
        return try {
            nodes.size
        } finally {
            nodes.forEach { it.releaseNode() }
        }
    }

    @JavascriptInterface
    fun clickById(viewId: String): Boolean =
        trace("点击 #$viewId") { clickNode(findById(viewId)) }

    @JavascriptInterface
    fun clickByText(text: String): Boolean =
        trace("点击文本 \"$text\"") { clickNode(findByText(text)) }

    @JavascriptInterface
    fun clickByDesc(desc: String): Boolean =
        trace("点击描述 \"$desc\"") { clickNode(findByDesc(desc)) }

    @JavascriptInterface
    fun longClickById(viewId: String): Boolean =
        trace("长按 #$viewId") { longClickNode(findById(viewId)) }

    @JavascriptInterface
    fun longClickByText(text: String): Boolean =
        trace("长按文本 \"$text\"") { longClickNode(findByText(text)) }

    @JavascriptInterface
    fun setTextById(viewId: String, text: String): Boolean = trace("设置 #$viewId 文本") {
        val node = findById(viewId) ?: return@trace false
        try {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            node.releaseNode()
        }
    }

    private inline fun trace(label: String, block: () -> Boolean): Boolean {
        val ok = block()
        logBus.debug("$label → 无障碍 ${if (ok) "✓" else "✗"}")
        return ok
    }

    @JavascriptInterface
    fun getTextById(viewId: String): String? {
        val node = findById(viewId) ?: return null
        return try {
            node.text?.toString()
        } finally {
            node.releaseNode()
        }
    }

    @JavascriptInterface
    fun getTextByText(text: String): String? {
        val node = findByText(text) ?: return null
        return try {
            node.text?.toString()
        } finally {
            node.releaseNode()
        }
    }

    @JavascriptInterface
    fun boundsById(viewId: String): String? {
        val node = findById(viewId) ?: return null
        return try {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            "${rect.left},${rect.top},${rect.right},${rect.bottom}"
        } finally {
            node.releaseNode()
        }
    }

    // ---- 新接口：条件查询 / 等待 / 滚动 ----

    /**
     * 返回第 [index] 个匹配元素（0 起）的 JSON，未找到返回 null。
     * 查询条件支持 id / text / textContains / textRegex / desc / descContains /
     * descRegex / className / clickable / editable / package。
     */
    @JavascriptInterface
    fun findRaw(criteriaJson: String, index: Int): String? {
        val criteria = parseCriteria(criteriaJson) ?: return null
        val target = index.coerceAtLeast(0)
        val node = controller()?.findFirst(criteria, target) ?: return null
        return try {
            node.toElementJson(target).toString()
        } finally {
            node.releaseNode()
        }
    }

    /** 返回全部匹配元素的 JSON 数组。 */
    @JavascriptInterface
    fun findAllRaw(criteriaJson: String): String {
        val criteria = parseCriteria(criteriaJson) ?: return "[]"
        return try {
            val nodes = controller()?.findAll(criteria) ?: return "[]"
            try {
                val array = JSONArray()
                nodes.forEachIndexed { index, node ->
                    array.put(node.toElementJson(index))
                }
                array.toString()
            } finally {
                nodes.forEach { it.releaseNode() }
            }
        } catch (t: Throwable) {
            logBus.warn("选择器 findAll 失败: ${t.message}")
            "[]"
        }
    }

    /** 匹配数量。 */
    @JavascriptInterface
    fun countRaw(criteriaJson: String): Int {
        val criteria = parseCriteria(criteriaJson) ?: return 0
        val nodes = controller()?.findAll(criteria) ?: return 0
        return try {
            nodes.size
        } finally {
            nodes.forEach { it.releaseNode() }
        }
    }

    @JavascriptInterface
    fun clickRaw(criteriaJson: String, index: Int): Boolean {
        val criteria = parseCriteria(criteriaJson) ?: return false
        val target = index.coerceAtLeast(0)
        val node = controller()?.findFirst(criteria, target) ?: return false
        val ok = clickNode(node)
        logBus.debug("选择器点击 ${criteria.summary()} #$target → 无障碍 ${if (ok) "✓" else "✗"}")
        return ok
    }

    @JavascriptInterface
    fun longClickRaw(criteriaJson: String, index: Int): Boolean {
        val criteria = parseCriteria(criteriaJson) ?: return false
        val target = index.coerceAtLeast(0)
        val node = controller()?.findFirst(criteria, target) ?: return false
        val ok = longClickNode(node)
        logBus.debug("选择器长按 ${criteria.summary()} #$target → 无障碍 ${if (ok) "✓" else "✗"}")
        return ok
    }

    /**
     * 轮询等待元素出现，返回该元素 JSON；超时、无无障碍或脚本被停止时返回 null。
     * 等待期间会响应「停止」，不会卡死调用线程。
     */
    @JavascriptInterface
    fun waitForRaw(
        criteriaJson: String,
        timeoutMs: Double,
        intervalMs: Double,
        index: Int,
    ): String? {
        val criteria = parseCriteria(criteriaJson) ?: return null
        val controller = controller() ?: return null
        val target = index.coerceAtLeast(0)
        val timeout = timeoutMs.toLong().coerceIn(0L, MAX_WAIT_MS)
        val interval = intervalMs.toLong().coerceIn(MIN_POLL_MS, MAX_POLL_MS)
        val started = System.currentTimeMillis()
        while (true) {
            if (isExitRequested()) return null
            val node = controller.findFirst(criteria, target)
            if (node != null) {
                return try {
                    node.toElementJson(target).toString()
                } finally {
                    node.releaseNode()
                }
            }
            val elapsed = System.currentTimeMillis() - started
            if (elapsed >= timeout) {
                logBus.debug("选择器等待 ${criteria.summary()} → 超时 ${timeout}ms ✗")
                return null
            }
            val sleepMs = minOf(interval, timeout - elapsed).coerceAtLeast(1L)
            runCatching { Thread.sleep(sleepMs) }
        }
    }

    /** 轮询等待元素消失；超时返回 false。 */
    @JavascriptInterface
    fun waitGoneRaw(criteriaJson: String, timeoutMs: Double, intervalMs: Double): Boolean {
        val criteria = parseCriteria(criteriaJson) ?: return false
        val controller = controller() ?: return false
        val timeout = timeoutMs.toLong().coerceIn(0L, MAX_WAIT_MS)
        val interval = intervalMs.toLong().coerceIn(MIN_POLL_MS, MAX_POLL_MS)
        val started = System.currentTimeMillis()
        while (true) {
            if (isExitRequested()) return false
            val node = controller.findFirst(criteria, 0)
            val found = node != null
            node?.releaseNode()
            if (!found) return true
            val elapsed = System.currentTimeMillis() - started
            if (elapsed >= timeout) {
                logBus.debug("选择器等待消失 ${criteria.summary()} → 超时 ${timeout}ms ✗")
                return false
            }
            runCatching { Thread.sleep(minOf(interval, timeout - elapsed).coerceAtLeast(1L)) }
        }
    }

    /** 尝试把匹配元素滚动到可见区域。 */
    @JavascriptInterface
    fun scrollIntoViewRaw(criteriaJson: String, index: Int): Boolean {
        val criteria = parseCriteria(criteriaJson) ?: return false
        val target = index.coerceAtLeast(0)
        val node = controller()?.findFirst(criteria, target) ?: return false
        return try {
            val ok = scrollIntoView(node)
            logBus.debug("选择器滚动到可见 ${criteria.summary()} → 无障碍 ${if (ok) "✓" else "✗"}")
            ok
        } finally {
            node.releaseNode()
        }
    }

    // ---- 内部实现 ----

    private fun controller(): AccessibilityController? = controllerProvider()

    private fun findById(viewId: String) =
        controller()?.findFirst(AccessibilityController.NodeCriteria(viewId = viewId), 0)

    private fun findByText(text: String) =
        controller()?.findFirst(AccessibilityController.NodeCriteria(text = text), 0)

    private fun findByDesc(desc: String) =
        controller()?.findFirst(AccessibilityController.NodeCriteria(desc = desc), 0)

    private fun AccessibilityNodeInfo?.exists(): Boolean {
        this ?: return false
        releaseNode()
        return true
    }

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return try {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                true
            } else {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                controller()?.click(rect.exactCenterX(), rect.exactCenterY()) ?: false
            }
        } finally {
            node.releaseNode()
        }
    }

    private fun longClickNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return try {
            if (node.isLongClickable && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                true
            } else {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                controller()?.longClick(
                    rect.exactCenterX(),
                    rect.exactCenterY(),
                    DEFAULT_LONG_CLICK_MS,
                ) ?: false
            }
        } finally {
            node.releaseNode()
        }
    }

    private fun scrollIntoView(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)) {
            return true
        }
        var parent = node.parent
        try {
            while (parent != null) {
                if (parent.isScrollable && parent.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    return true
                }
                val next = parent.parent
                parent.releaseNode()
                parent = next
            }
        } finally {
            parent?.releaseNode()
        }
        return false
    }

    private fun AccessibilityNodeInfo.toElementJson(index: Int): JSONObject {
        val rect = Rect()
        getBoundsInScreen(rect)
        return JSONObject().apply {
            put("id", viewIdResourceName ?: JSONObject.NULL)
            put("text", text?.toString() ?: JSONObject.NULL)
            put("desc", contentDescription?.toString() ?: JSONObject.NULL)
            put("className", className?.toString() ?: JSONObject.NULL)
            put("package", packageName ?: JSONObject.NULL)
            put("left", rect.left)
            put("top", rect.top)
            put("right", rect.right)
            put("bottom", rect.bottom)
            put("x", rect.exactCenterX().toInt())
            put("y", rect.exactCenterY().toInt())
            put("width", rect.width())
            put("height", rect.height())
            put("clickable", isClickable)
            put("longClickable", isLongClickable)
            put("editable", isEditable)
            put("scrollable", isScrollable)
            put("enabled", isEnabled)
            put("index", index)
        }
    }

    private fun parseCriteria(json: String): AccessibilityController.NodeCriteria? = try {
        val obj = if (json.isBlank()) JSONObject() else JSONObject(json)
        val textRegex = obj.optionalString("textRegex")
        val descRegex = obj.optionalString("descRegex")
        validateRegex("textRegex", textRegex)
        validateRegex("descRegex", descRegex)
        AccessibilityController.NodeCriteria(
            viewId = obj.optionalString("id", "viewId"),
            text = obj.optionalString("text"),
            textContains = obj.optionalString("textContains", "contains"),
            textRegex = textRegex,
            desc = obj.optionalString("desc", "description", "contentDescription"),
            descContains = obj.optionalString("descContains"),
            descRegex = descRegex,
            className = obj.optionalString("className", "class"),
            clickable = obj.optionalBoolean("clickable"),
            editable = obj.optionalBoolean("editable"),
            packageName = obj.optionalString("package", "packageName"),
        )
    } catch (t: Throwable) {
        logBus.warn("选择器参数无效: ${t.message}")
        null
    }

    private fun validateRegex(name: String, pattern: String?) {
        if (pattern == null) return
        try {
            Regex(pattern)
        } catch (t: Throwable) {
            throw IllegalArgumentException("$name 不是合法的正则表达式: ${t.message}")
        }
    }

    private fun JSONObject.optionalString(vararg keys: String): String? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val value = optString(key)
            if (value.isNotEmpty()) return value
        }
        return null
    }

    private fun JSONObject.optionalBoolean(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null

    private fun AccessibilityController.NodeCriteria.summary(): String = buildString {
        append("{")
        val parts = listOfNotNull(
            viewId?.let { "id=$it" },
            text?.let { "text=$it" },
            textContains?.let { "textContains=$it" },
            textRegex?.let { "textRegex=$it" },
            desc?.let { "desc=$it" },
            descContains?.let { "descContains=$it" },
            descRegex?.let { "descRegex=$it" },
            className?.let { "class=$it" },
            clickable?.let { "clickable=$it" },
            editable?.let { "editable=$it" },
            packageName?.let { "package=$it" },
        )
        append(parts.joinToString(","))
        append("}")
    }

    private companion object {
        const val DEFAULT_LONG_CLICK_MS = 700L
        const val MIN_POLL_MS = 16L
        const val MAX_POLL_MS = 5_000L
        const val MAX_WAIT_MS = 10L * 60L * 1000L
    }
}
