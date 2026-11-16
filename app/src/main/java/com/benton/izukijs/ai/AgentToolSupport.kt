package com.benton.izukijs.ai

import com.benton.izukijs.controller.NodeSnapshot
import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.ocr.OcrBlock
import com.benton.izukijs.ocr.OcrLineMatch
import com.benton.izukijs.ocr.OcrLineSynthesis
import com.benton.izukijs.ocr.OcrMatcher
import com.benton.izukijs.virtualscreen.ScreenTarget
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

/**
 * 一次性读取控件树；首次因预算不足超时时放大预算重试一次。
 *
 * 慢设备第一次读取时还没有本机基线，固定 1.5s 必然超时；重试成功后
 * [UiautomatorDumpBudget] 会记录真实耗时，后续读取直接使用更大的预算。
 */
internal fun AgentTools.probeTree(initialBudgetMs: Long): NodeTreeResult =
    TreeProbe.probe(initialBudgetMs, provider = nodeTreeProvider)

/** 在 [deadline] 内取一次控件树；预算不足时返回 null（调用方应直接放弃本轮）。 */
internal fun AgentTools.treeProbe(deadline: Long): NodeTreeResult? =
    TreeProbe.treeProbe(deadline, provider = nodeTreeProvider)

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

internal data class SearchOutcome(
    val matches: JSONArray,
    /** 本次查找尝试过控件树但读取失败时的原因，供调用方在 0 命中时给出提示。 */
    val treeFailure: NodeTreeResult.Failure? = null,
    /** 控件树实际节点数；未读取控件树时为 null。 */
    val treeNodes: Int? = null,
    /** 本次查找在控件树上花费的 dump 时间；未读取控件树时为 null。 */
    val dumpMs: Long? = null,
    /** 控件树尝试次数（0 命中时会二次取树）。 */
    val treeAttempts: Int = 0,
    /** 控件树快照；供 explain / codegen 复用，避免二次 dump。 */
    val treeRoot: NodeSnapshot? = null,
)

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
): SearchOutcome {
    val matches = JSONArray()
    var treeFailure: NodeTreeResult.Failure? = null
    var treeNodes: Int? = null
    var dumpMs: Long? = null
    var treeAttempts = 0
    var treeRoot: NodeSnapshot? = null
    fun addUiMatches(predicate: (NodeSnapshot) -> Boolean) {
        val collected = collectNodesResult(predicate)
        treeFailure = collected.failure
        treeNodes = collected.nodeCount
        dumpMs = collected.dumpMs
        treeAttempts = collected.attempts
        treeRoot = collected.root
        collected.nodes.forEach { matches.put(nodeLabel(it)) }
    }
    fun outcome() = SearchOutcome(matches, treeFailure, treeNodes, dumpMs, treeAttempts, treeRoot)
    if (by == "id" && value != null) {
        addUiMatches { it.viewId == value }
        return outcome()
    }
    if (by == "desc" && value != null) {
        addUiMatches { it.contentDescription?.contains(value) == true }
        return outcome()
    }
    val query = text ?: value ?: return outcome()
    fun uiMatches() {
        addUiMatches {
            it.text?.contains(query) == true || it.contentDescription?.contains(query) == true
        }
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
    return outcome()
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

/**
 * 返回屏幕坐标 (x,y) 下最深的可点击祖先；只有根节点命中时不再拿根节点冒充命中。
 *
 * 控件树偶发只解析出根节点或布局容器，调用方需要区分「真的点到控件」和
 * 「读树失败 / 只拿到根节点」，因此 [HitProbe.reason] 会说明具体原因。
 */
internal fun AgentTools.hitProbe(x: Double, y: Double): HitProbe {
    val result = probeTree(HIT_PROBE_BUDGET_MS)
    val root = result.rootOrNull()
        ?: return HitProbe(checked = false, reason = failureMessage(result))
    val chain = hitChain(root, x.toInt(), y.toInt())
    if (chain.isEmpty()) return HitProbe(checked = true, reason = "坐标不在控件树范围内")
    val clickable = chain.lastOrNull { it.clickable }
    val node = clickable ?: chain.last()
    if (node === root) {
        return HitProbe(checked = true, reason = "仅命中根节点，坐标处没有可校验控件")
    }
    return HitProbe(
        checked = true,
        hit = nodeLabel(node),
        reason = if (clickable != null) "命中可点击控件" else "命中控件（不可点击）",
    )
}

/** 坐标命中的节点链，从根到最深节点；用于选择最深可点击祖先。 */
internal fun hitChain(
    root: NodeSnapshot,
    x: Int,
    y: Int,
): List<NodeSnapshot> {
    if (x < root.left || x > root.right || y < root.top || y > root.bottom) return emptyList()
    val children = root.children
        .mapNotNull { child -> hitChain(child, x, y).takeIf { it.isNotEmpty() } }
        .maxByOrNull { it.size }
        .orEmpty()
    return listOf(root) + children
}

internal data class CollectedNodes(
    val nodes: List<NodeSnapshot>,
    val failure: NodeTreeResult.Failure? = null,
    val nodeCount: Int = 0,
    val dumpMs: Long = 0L,
    val attempts: Int = 0,
    val root: NodeSnapshot? = null,
)

internal fun AgentTools.collectNodesResult(predicate: (NodeSnapshot) -> Boolean): CollectedNodes {
    var totalDumpMs = 0L
    var attempts = 0
    var lastCount = 0
    var lastRoot: NodeSnapshot? = null
    while (attempts < UI_MISS_RETRIES) {
        attempts++
        val started = System.nanoTime()
        val result = probeTree(TREE_PROBE_BUDGET_MS)
        totalDumpMs += (System.nanoTime() - started) / 1_000_000L
        val root = result.rootOrNull() ?: return CollectedNodes(
            nodes = emptyList(),
            failure = result as? NodeTreeResult.Failure,
            nodeCount = lastCount,
            dumpMs = totalDumpMs,
            attempts = attempts,
        )
        lastCount = countNodes(root)
        lastRoot = root
        val out = ArrayList<NodeSnapshot>()
        fun walk(node: NodeSnapshot) {
            if (predicate(node)) out.add(node)
            node.children.forEach(::walk)
        }
        walk(root)
        if (out.isNotEmpty()) {
            return CollectedNodes(
                out,
                nodeCount = lastCount,
                dumpMs = totalDumpMs,
                attempts = attempts,
                root = root,
            )
        }
        // 0 命中可能是界面过渡帧或 dump 拿到旧树；只在失败路径上多取一次。
        if (attempts < UI_MISS_RETRIES) sleepQuietly(UI_MISS_RETRY_DELAY_MS)
    }
    return CollectedNodes(
        nodes = emptyList(),
        nodeCount = lastCount,
        dumpMs = totalDumpMs,
        attempts = attempts,
        root = lastRoot,
    )
}

internal fun AgentTools.collectNodes(predicate: (NodeSnapshot) -> Boolean): List<NodeSnapshot> =
    collectNodesResult(predicate).nodes

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
    return if (normalized) raw * axisSize(args, key) else raw
}

private fun AgentTools.axisSize(args: JSONObject, key: String): Int {
    val main = ScreenTarget.from(args.optString("target")) == ScreenTarget.MAIN
    val horizontal = key == "x" || key == "x1" || key == "x2"
    return when {
        horizontal && main -> deviceApi.mainScreenWidth()
        horizontal -> deviceApi.screenWidth()
        main -> deviceApi.mainScreenHeight()
        else -> deviceApi.screenHeight()
    }
}

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
