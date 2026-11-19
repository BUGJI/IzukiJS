package com.benton.izukijs.runtime.api

import com.benton.izukijs.controller.NodeSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把控件树快照渲染成 Auto.js `auto.dump` 使用的节点 JSON。
 *
 * 保持纯 Kotlin：输入只有 [NodeSnapshot]，便于 JVM 单测；Android 侧只负责取树。
 * 字段名对齐 Auto.js 旧脚本习惯（`bounds` / `visibleToUser` / `indexInParent`），
 * 不存在的底层信息（如 `idHex`）显式给 `null`，不伪造数值。
 */
object SelectorDump {

    /** `auto.dump` 支持的选项；缺省值与 Auto.js 一致。 */
    data class Options(
        val maxDepth: Int = -1,
        val maxNodes: Int = 0,
        val visibleOnly: Boolean = false,
        val properties: Set<String> = emptySet(),
    )

    private val STANDARD_PROPERTIES = linkedSetOf(
        "className", "id", "idHex", "text", "desc", "bounds", "packageName",
        "indexInParent", "depth", "childCount", "clickable", "longClickable",
        "scrollable", "checkable", "checked", "enabled", "focusable", "focused",
        "selected", "editable", "visibleToUser", "password", "actions",
    )

    /**
     * 单次 dump 的硬上限。结果会作为一条 JSON 字符串跨 QuickJS 桥，节点过多时
     * 字符串可达数十 MB；20k 已远超常见窗口，超出部分以 `truncated` 标记。
     */
    private const val HARD_NODE_LIMIT = 20_000

    fun parseOptions(json: String?): Options {
        val obj = try {
            if (json.isNullOrBlank()) JSONObject() else JSONObject(json)
        } catch (t: Throwable) {
            JSONObject()
        }
        val properties = LinkedHashSet<String>()
        val array = obj.optJSONArray("properties")
        if (array != null) {
            for (i in 0 until array.length()) {
                val name = array.optString(i)
                if (name.isNotEmpty()) properties += name
            }
        }
        return Options(
            maxDepth = obj.optInt("maxDepth", -1),
            maxNodes = obj.optInt("maxNodes", 0),
            visibleOnly = obj.optBoolean("visibleOnly", false),
            properties = properties,
        )
    }

    fun render(root: NodeSnapshot, options: Options): JSONObject {
        val walker = Walker(options)
        val tree = walker.visit(root, 0)
        return JSONObject().apply {
            put("ok", true)
            put("tree", tree ?: JSONObject.NULL)
            put("total", walker.emitted)
            put("truncated", walker.truncated)
        }
    }

    private class Walker(
        private val options: Options,
    ) {
        private val limit = when {
            options.maxNodes <= 0 -> HARD_NODE_LIMIT
            else -> minOf(options.maxNodes, HARD_NODE_LIMIT)
        }
        private val wanted = options.properties.takeIf { it.isNotEmpty() }

        var emitted = 0
            private set
        var truncated = false
            private set

        fun visit(node: NodeSnapshot, depth: Int): JSONObject? {
            if (options.maxDepth >= 0 && depth > options.maxDepth) {
                truncated = true
                return null
            }
            if (options.visibleOnly && !node.visible) return null
            if (emitted >= limit) {
                truncated = true
                return null
            }
            emitted++
            val obj = JSONObject()
            writeNode(obj, node, depth)
            val children = JSONArray()
            for (child in node.children) {
                val rendered = visit(child, depth + 1) ?: continue
                children.put(rendered)
            }
            obj.put("children", children)
            return obj
        }

        private fun writeNode(obj: JSONObject, node: NodeSnapshot, depth: Int) {
            val bounds = JSONObject().apply {
                put("left", node.left)
                put("top", node.top)
                put("right", node.right)
                put("bottom", node.bottom)
            }
            val values = linkedMapOf<String, Any?>(
                "className" to node.className,
                "id" to node.viewId,
                // QuickJS 侧拿不到 Android 资源 id 的数值形式，保持 null 而不是猜一个。
                "idHex" to null,
                "text" to node.text,
                "desc" to node.contentDescription,
                "bounds" to bounds,
                "packageName" to node.packageName,
                "indexInParent" to node.indexInParent,
                "depth" to depth,
                "childCount" to node.children.size,
                "clickable" to node.clickable,
                "longClickable" to node.longClickable,
                "scrollable" to node.scrollable,
                "checkable" to node.checkable,
                "checked" to node.checked,
                "enabled" to node.enabled,
                "focusable" to node.focusable,
                "focused" to node.focused,
                "selected" to node.selected,
                "editable" to node.editable,
                "visibleToUser" to node.visible,
                "password" to node.password,
                "actions" to actions(node),
            )
            val names = wanted?.filter { it in STANDARD_PROPERTIES } ?: STANDARD_PROPERTIES
            for (name in names) {
                obj.put(name, values[name] ?: JSONObject.NULL)
            }
        }

        /** 由节点标志推导可用动作名；真实 ActionList 需要节点句柄，这里只做只读近似。 */
        private fun actions(node: NodeSnapshot): JSONArray = JSONArray().apply {
            if (node.clickable) put("click")
            if (node.longClickable) put("long_click")
            if (node.editable) put("set_text")
            if (node.scrollable) {
                put("scroll_forward")
                put("scroll_backward")
            }
            if (node.checkable) put(if (node.checked) "uncheck" else "check")
            if (node.focusable) put("focus")
            if (node.selected) put("clear_selection")
        }
    }
}
