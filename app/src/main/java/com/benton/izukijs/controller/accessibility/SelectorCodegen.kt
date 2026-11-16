package com.benton.izukijs.controller.accessibility

import com.benton.izukijs.controller.NodeSnapshot
import org.json.JSONObject

/**
 * 从当前控件树为一个已命中的节点生成更稳定、尽量唯一的选择器 DSL。
 *
 * 规则优先 id / text / desc，其次 class + 可点击；无法唯一时返回匹配数最少的候选，
 * 由调用方决定是否继续补充条件。
 */
internal object SelectorCodegen {

    fun generate(root: NodeSnapshot, criteria: AccessibilityController.NodeCriteria): JSONObject {
        val nodes = SelectorExplain.flatten(root)
        val target = nodes.firstOrNull { criteria.matchesSnapshot(it) }
            ?: return JSONObject().apply {
                put("ok", false)
                put("message", "没有匹配的节点，无法生成选择器")
            }

        val candidates = buildCandidates(target)
        if (candidates.isEmpty()) {
            return JSONObject().apply {
                put("ok", false)
                put("message", "目标节点没有可用于生成选择器的属性")
            }
        }

        var best: Triple<String, Int, Int>? = null
        for ((dsl, weight) in candidates) {
            val parsed = runCatching { SelectorDsl.parse(dsl) }.getOrNull() ?: continue
            val matches = nodes.count { parsed.matchesSnapshot(it) }
            val current = best
            if (current == null ||
                (matches == 1 && current.second != 1) ||
                (matches == current.second && weight < current.third) ||
                (matches in 2 until current.second)
            ) {
                best = Triple(dsl, matches, weight)
            }
        }
        val chosen = best
            ?: return JSONObject().apply {
                put("ok", false)
                put("message", "候选选择器均无法解析")
            }
        val unique = chosen.second == 1
        return JSONObject().apply {
            put("ok", true)
            put("dsl", chosen.first)
            put("js", "selector.click(" + JSONObject.quote(chosen.first) + ")")
            put("matches", chosen.second)
            put("unique", unique)
            put("confidence", confidenceOf(chosen.third, unique))
            if (!unique) put("reason", "当前控件树内有 ${chosen.second} 个节点匹配，建议补充条件或改用坐标兜底")
        }
    }

    private fun buildCandidates(node: NodeSnapshot): List<Pair<String, Int>> {
        val out = LinkedHashSet<Pair<String, Int>>()
        node.viewId?.takeIf { it.isNotBlank() }?.let { id ->
            out.add("id=${dslValue(id)}" to 0)
        }
        node.text?.takeIf { it.isNotBlank() }?.let { text ->
            out.add("text=${dslValue(text)}" to 1)
            node.className?.takeIf { it.isNotBlank() }?.let { className ->
                out.add("text=${dslValue(text)} class=${dslValue(className)}" to 2)
            }
        }
        node.contentDescription?.takeIf { it.isNotBlank() }?.let { desc ->
            out.add("desc=${dslValue(desc)}" to 2)
            node.className?.takeIf { it.isNotBlank() }?.let { className ->
                out.add("desc=${dslValue(desc)} class=${dslValue(className)}" to 3)
            }
        }
        node.className?.takeIf { it.isNotBlank() }?.let { className ->
            out.add("class=${dslValue(className)}" to 4)
            if (node.clickable) out.add("class=${dslValue(className)} clickable=true" to 3)
        }
        if (out.isEmpty() && node.clickable) out.add("clickable=true" to 5)
        return out.toList()
    }

    private fun dslValue(value: String): String {
        val needsQuote = value.any {
            it.isWhitespace() || it == '"' || it == '\'' || it == '=' || it == '~' || it == '^' || it == '$'
        }
        if (!needsQuote) return value
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private fun confidenceOf(weight: Int, unique: Boolean): Double {
        val base = when (weight) {
            0 -> 0.95
            1 -> 0.90
            2 -> 0.82
            3 -> 0.75
            4 -> 0.65
            else -> 0.55
        }
        return if (unique) base else base * 0.6
    }
}
