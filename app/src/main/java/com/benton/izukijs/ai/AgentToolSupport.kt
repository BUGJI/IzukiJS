package com.benton.izukijs.ai

import com.benton.izukijs.controller.NodeSnapshot
import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.ocr.OcrBlock
import com.benton.izukijs.ocr.OcrLineMatch
import com.benton.izukijs.ocr.OcrLineSynthesis
import com.benton.izukijs.ocr.OcrMatcher
import org.json.JSONArray
import org.json.JSONObject

internal fun AgentTools.blockedJson(message: String): String = jsonOf {
    put("ok", false)
    put("blocked", true)
    put("message", message)
}

/** 工具返回文本是否代表失败：空、以 [错误] 开头，或 JSON 中 ok=false。 */
internal fun AgentTools.resultIsFailure(result: String): Boolean {
    val trimmed = result.trim()
    if (trimmed.isEmpty()) return true
    if (trimmed.startsWith("[错误]")) return true
    if (!trimmed.startsWith("{")) return false
    return runCatching { !JSONObject(trimmed).optBoolean("ok", true) }.getOrDefault(false)
}

/** 在 [deadline] 内取一次控件树；预算不足时返回 null（调用方应直接放弃本轮）。 */
internal fun AgentTools.treeProbe(deadline: Long): NodeTreeResult? {
    val remaining = deadline - System.currentTimeMillis()
    if (remaining < MIN_PROBE_MS) return null
    return nodeTreeProvider(minOf(remaining, TREE_PROBE_BUDGET_MS))
}

internal fun AgentTools.failureMessage(result: NodeTreeResult?): String = when (result) {
    null -> "等待超时"
    NodeTreeResult.NoBackend -> "需开启无障碍或 Shizuku"
    is NodeTreeResult.Failure -> result.message
    is NodeTreeResult.Success -> "控件树为空"
}

internal fun AgentTools.waitIdle(settleMs: Long) {
    val deadline = System.currentTimeMillis() + SETTLE_MAX_MS
    val first = treeProbe(deadline)
    val root = first?.rootOrNull()
    if (root == null) {
        sleepQuietly(minOf(settleMs, SETTLE_MAX_MS))
        return
    }
    var last: String? = signature(root)
    var stableSince = System.currentTimeMillis()
    while (System.currentTimeMillis() < deadline) {
        sleepQuietly(minOf(SETTLE_POLL_MS, deadline - System.currentTimeMillis()).coerceAtLeast(1L))
        val result = treeProbe(deadline) ?: return
        val nextRoot = result.rootOrNull()
        if (nextRoot == null) {
            last = null
            stableSince = System.currentTimeMillis()
            continue
        }
        val next = signature(nextRoot)
        if (next == last) {
            if (System.currentTimeMillis() - stableSince >= settleMs) return
        } else {
            last = next
            stableSince = System.currentTimeMillis()
        }
    }
}

/**
 * 统一的元素查找：by=id/desc 精确匹配控件，否则按 text 模糊匹配控件与 OCR。
 *
 * [source] 控制取数来源：`ui` 只读控件树（较慢但精确），`ocr` 只识别屏幕文字（最快），
 * `both` 双源交叉验证，`auto` 先 OCR、未命中再回退控件树（纯文字查找的推荐默认值，可避免
 * 在有动画的界面上白等一次 `uiautomator dump`）。
 */
internal fun AgentTools.search(
    by: String?,
    value: String?,
    text: String?,
    source: String = SOURCE_BOTH,
    fuzzy: Boolean = true,
    minConfidence: Double = 0.0,
): JSONArray {
    val matches = JSONArray()
    if (by == "id" && value != null) {
        collectNodes { it.viewId == value }.forEach { matches.put(nodeLabel(it)) }
        return matches
    }
    if (by == "desc" && value != null) {
        collectNodes { it.contentDescription?.contains(value) == true }.forEach { matches.put(nodeLabel(it)) }
        return matches
    }
    val query = text ?: value ?: return matches
    fun uiMatches() {
        collectNodes {
            it.text?.contains(query) == true || it.contentDescription?.contains(query) == true
        }.forEach { matches.put(nodeLabel(it)) }
    }
    when (source) {
        SOURCE_UI -> uiMatches()
        SOURCE_OCR -> ocrBlocks(query, fuzzy, minConfidence).forEach { matches.put(it) }
        SOURCE_AUTO -> {
            ocrBlocks(query, fuzzy, minConfidence).forEach { matches.put(it) }
            if (matches.length() == 0) uiMatches()
        }
        else -> {
            uiMatches()
            ocrBlocks(query, fuzzy, minConfidence).forEach { matches.put(it) }
        }
    }
    return matches
}

/** 解析 OCR 结构化结果并按行聚合匹配，返回统一格式的匹配项。 */
private fun AgentTools.ocrBlocks(
    query: String,
    fuzzy: Boolean = true,
    minConfidence: Double = 0.0,
): List<JSONObject> = ocrLineMatches(query, fuzzy, minConfidence).map { match ->
    val line = match.line
    JSONObject().apply {
        put("source", "ocr")
        put("text", line.text)
        put("center", JSONArray().put(line.x).put(line.y))
        put("bounds", JSONArray().put(line.left).put(line.top).put(line.right).put(line.bottom))
        put("line", true)
        put("block_count", line.blockCount)
        if (match.fuzzy) {
            put("fuzzy", true)
            put("similarity", match.similarity)
        }
        line.confidence?.let { put("conf", it) }
    }
}

internal fun AgentTools.ocrLineMatches(
    query: String,
    fuzzy: Boolean,
    minConfidence: Double,
): List<OcrLineMatch> {
    val json = ocrApi.recognizeBlocksJson() ?: return emptyList()
    val array = runCatching { JSONObject(json).optJSONArray("blocks") }.getOrNull() ?: return emptyList()
    val blocks = ArrayList<OcrBlock>(array.length())
    for (i in 0 until array.length()) {
        val block = array.optJSONObject(i) ?: continue
        val text = block.optString("text")
        if (text.isBlank()) continue
        val x = block.optInt("x")
        val y = block.optInt("y")
        val w = block.optInt("w")
        val h = block.optInt("h")
        blocks.add(
            OcrBlock(
                text = text,
                left = block.optInt("left", x - w / 2),
                top = block.optInt("top", y - h / 2),
                right = block.optInt("right", x + w / 2),
                bottom = block.optInt("bottom", y + h / 2),
                confidence = if (block.has("conf")) block.optDouble("conf") else null,
                suspicious = block.optBoolean("suspicious", false),
            ),
        )
    }
    val lines = OcrLineSynthesis.synthesize(blocks)
    return OcrMatcher.match(lines, query, fuzzy = fuzzy, minConfidence = minConfidence)
}

// ---- 命中测试 ----

internal data class HitProbe(
    val checked: Boolean,
    val hit: JSONObject? = null,
    val reason: String? = null,
)

/** 返回屏幕坐标 (x,y) 下最深的控件快照标签，并区分「没有控件树」与「有树但无命中」。 */
internal fun AgentTools.hitProbe(x: Double, y: Double): HitProbe {
    val result = nodeTreeProvider(HIT_PROBE_BUDGET_MS)
    val root = result.rootOrNull()
        ?: return HitProbe(checked = false, reason = failureMessage(result))
    val node = deepestAt(root, x.toInt(), y.toInt()) ?: return HitProbe(checked = true)
    return HitProbe(checked = true, hit = nodeLabel(node))
}

private fun AgentTools.deepestAt(node: NodeSnapshot, x: Int, y: Int): NodeSnapshot? {
    if (x < node.left || x > node.right || y < node.top || y > node.bottom) return null
    var best: NodeSnapshot = node
    for (child in node.children) {
        deepestAt(child, x, y)?.let { best = it }
    }
    return best
}

internal fun AgentTools.collectNodes(predicate: (NodeSnapshot) -> Boolean): List<NodeSnapshot> {
    val root = nodeTreeProvider(TREE_PROBE_BUDGET_MS).rootOrNull() ?: return emptyList()
    val out = ArrayList<NodeSnapshot>()
    fun walk(node: NodeSnapshot) {
        if (predicate(node)) out.add(node)
        node.children.forEach(::walk)
    }
    walk(root)
    return out
}

private fun AgentTools.nodeLabel(node: NodeSnapshot): JSONObject = JSONObject().apply {
    put("source", "ui")
    node.text?.takeIf { it.isNotBlank() }?.let { put("text", it.take(MAX_TEXT_CHARS)) }
    node.viewId?.takeIf { it.isNotBlank() }?.let { put("id", it) }
    node.contentDescription?.takeIf { it.isNotBlank() }?.let { put("desc", it.take(MAX_TEXT_CHARS)) }
    node.className?.substringAfterLast('.')?.takeIf { it.isNotBlank() }?.let { put("class", it) }
    put("center", JSONArray().put((node.left + node.right) / 2).put((node.top + node.bottom) / 2))
    put("bounds", JSONArray().put(node.left).put(node.top).put(node.right).put(node.bottom))
    put("clickable", node.clickable)
}

/** 控件树签名，用于判定界面是否稳定。 */
internal fun AgentTools.signature(root: NodeSnapshot): String {
    val out = StringBuilder()
    fun walk(node: NodeSnapshot) {
        out.append(node.viewId).append('|')
            .append(node.text).append('|')
            .append(node.left).append(',').append(node.top).append(',')
            .append(node.right).append(',').append(node.bottom).append(';')
        node.children.forEach(::walk)
    }
    walk(root)
    return out.toString()
}

// ---- 坐标 ----

internal fun AgentTools.resolveX(args: JSONObject): Double? = coordinate(args, "x", args.optBoolean("normalized", false))

internal fun AgentTools.resolveY(args: JSONObject): Double? = coordinate(args, "y", args.optBoolean("normalized", false))

internal fun AgentTools.coordinate(args: JSONObject, key: String, normalized: Boolean): Double? {
    if (!args.has(key)) return null
    val raw = args.optDouble(key, Double.NaN)
    if (!raw.isFinite()) return null
    return if (normalized) raw * axisSize(key) else raw
}

private fun AgentTools.axisSize(key: String): Int =
    if (key == "x" || key == "x1" || key == "x2") deviceApi.screenWidth() else deviceApi.screenHeight()

// ---- 结果构造 ----

internal fun AgentTools.okResult(success: Boolean, action: String): String = jsonOf {
    put("ok", success)
    if (!success) put("message", "$action 失败（当前控制模式不支持或目标无效）")
}

internal fun AgentTools.jsonOf(block: JSONObject.() -> Unit): String = JSONObject().apply(block).toString()

internal fun AgentTools.errorJson(message: String): String = jsonOf {
    put("ok", false)
    put("message", message)
}

internal fun AgentTools.sleepQuietly(millis: Long) {
    if (millis <= 0) return
    runCatching { Thread.sleep(millis) }
}

internal fun AgentTools.countNodes(root: NodeSnapshot): Int {
    var count = 1
    root.children.forEach { count += countNodes(it) }
    return count
}

/** 从读取结果中取出控件树；无后端或读取失败时返回 null。 */
internal fun NodeTreeResult.rootOrNull(): NodeSnapshot? = (this as? NodeTreeResult.Success)?.root

internal fun AgentTools.appendNode(node: NodeSnapshot, depth: Int, out: StringBuilder) {
    if (out.length > MAX_DUMP_CHARS) return
    val label = buildString {
        node.className?.substringAfterLast('.')?.let { append(it) }
        node.viewId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { append("#").append(it) }
        node.text?.takeIf { it.isNotBlank() }?.let { append(" \"").append(it.take(MAX_TEXT_CHARS)).append("\"") }
        node.contentDescription?.takeIf { it.isNotBlank() }
            ?.let { append(" desc=\"").append(it.take(MAX_TEXT_CHARS)).append("\"") }
    }
    if (label.isNotBlank()) {
        out.append("  ".repeat(depth)).append(label)
        if (node.clickable) out.append(" [可点]")
        val cx = (node.left + node.right) / 2
        val cy = (node.top + node.bottom) / 2
        out.append(" @(").append(cx).append(",").append(cy).append(")\n")
    }
    node.children.forEach { appendNode(it, depth + 1, out) }
}
