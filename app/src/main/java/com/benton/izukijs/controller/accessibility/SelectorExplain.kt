package com.benton.izukijs.controller.accessibility

import com.benton.izukijs.controller.NodeSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * 选择器诊断：一次读取控件树，逐条应用条件并报告哪一条把结果筛空。
 *
 * 输入是纯 [NodeSnapshot]，因此可以脱离无障碍框架做 JVM 单测。
 */
internal object SelectorExplain {

    fun explain(root: NodeSnapshot, criteria: AccessibilityController.NodeCriteria): JSONObject {
        val nodes = flatten(root)
        val conditions = SelectorDsl.conditions(criteria)
        val remaining = nodes.toMutableList()
        val conditionArray = JSONArray()
        var firstEmpty: String? = null

        for (condition in conditions) {
            val before = remaining.size
            remaining.removeAll { !condition.test(it) }
            conditionArray.put(
                JSONObject().apply {
                    put("key", condition.key)
                    put("value", condition.value)
                    put("before", before)
                    put("matches", remaining.size)
                },
            )
            if (remaining.isEmpty()) {
                firstEmpty = condition.key
                break
            }
        }

        val nearMisses = JSONArray()
        for (node in nodes) {
            val failed = conditions.filterNot { it.test(node) }
            if (failed.size == 1) {
                nearMisses.put(nodeJson(node, reason = failed.first().key))
                if (nearMisses.length() >= 5) break
            }
        }

        return JSONObject().apply {
            put("ok", true)
            put("totalNodes", nodes.size)
            put("conditions", conditionArray)
            put("firstEmpty", firstEmpty ?: JSONObject.NULL)
            put("nearMisses", nearMisses)
            put(
                "samples",
                JSONArray().apply {
                    remaining.take(5).forEach { put(nodeJson(it)) }
                },
            )
        }
    }

    fun flatten(root: NodeSnapshot): List<NodeSnapshot> {
        val out = ArrayList<NodeSnapshot>()
        fun walk(node: NodeSnapshot) {
            out.add(node)
            node.children.forEach(::walk)
        }
        walk(root)
        return out
    }

    fun nodeJson(node: NodeSnapshot, reason: String? = null): JSONObject = JSONObject().apply {
        put("id", node.viewId ?: JSONObject.NULL)
        put("text", node.text ?: JSONObject.NULL)
        put("desc", node.contentDescription ?: JSONObject.NULL)
        put("className", node.className ?: JSONObject.NULL)
        put("package", node.packageName ?: JSONObject.NULL)
        put("clickable", node.clickable)
        put("editable", node.editable)
        put("scrollable", node.scrollable)
        put("enabled", node.enabled)
        put("visible", node.visible)
        put("left", node.left)
        put("top", node.top)
        put("width", (node.right - node.left).coerceAtLeast(0))
        put("height", (node.bottom - node.top).coerceAtLeast(0))
        put("depth", node.depth)
        put("index", node.indexInParent)
        reason?.let { put("reason", it) }
    }
}
