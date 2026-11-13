package com.benton.izukijs.ocr

/**
 * 由同一行的多个 OCR 块合成的「行」。
 *
 * OCR 引擎经常把一句话切成多块（`Read` / `timed` / `out`），按单块匹配会导致整句失配。
 * [text] 用空格连接块文本，[left]/[top]/[right]/[bottom] 为整行包围盒。
 */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Double? = null,
    val blockCount: Int = 1,
) {
    val x: Int get() = (left + right) / 2

    val y: Int get() = (top + bottom) / 2

    val width: Int get() = (right - left).coerceAtLeast(0)

    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

/** 行文本匹配结果；[fuzzy] 为 true 表示精确匹配失败后按编辑距离命中。 */
data class OcrLineMatch(
    val line: OcrLine,
    val fuzzy: Boolean = false,
    val similarity: Double = 1.0,
)

/**
 * 把单块 OCR 结果按行聚合。
 *
 * 判定规则：
 * - 垂直中心差不超过较高块高度的一半，视为同一行；
 * - 同一行内按 x 排序，水平间距超过行高的一定倍数时拆段，避免把左右两栏拼成一句。
 */
object OcrLineSynthesis {

    /**
     * 行聚合参数。默认值保持原有自适应行为；显式指定时用于脚本 / MCP 的稳定输出。
     *
     * [maxGapY] 为同一行的最大垂直中心差（像素），[maxGapX] 为同行拆段的最大水平间距。
     */
    data class Options(
        val maxGapY: Int? = null,
        val maxGapX: Int? = null,
    )

    fun synthesize(blocks: List<OcrBlock>, options: Options = Options()): List<OcrLine> {
        if (blocks.isEmpty()) return emptyList()
        val sorted = blocks.sortedWith(compareBy({ it.y }, { it.left }))
        val rows = ArrayList<MutableList<OcrBlock>>()
        for (block in sorted) {
            val row = rows.firstOrNull { sameRow(it, block, options.maxGapY) }
            if (row == null) {
                rows.add(mutableListOf(block))
            } else {
                row.add(block)
            }
        }
        val out = ArrayList<OcrLine>(rows.size)
        rows.forEach { row -> out.addAll(splitIntoSegments(row, options.maxGapX)) }
        return out.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun sameRow(
        row: MutableList<OcrBlock>,
        block: OcrBlock,
        maxGapY: Int?,
    ): Boolean {
        val rowTop = row.minOf { it.top }
        val rowBottom = row.maxOf { it.bottom }
        val rowHeight = (rowBottom - rowTop).coerceAtLeast(1)
        val overlap = minOf(rowBottom, block.bottom) - maxOf(rowTop, block.top)
        if (overlap > 0 && overlap >= minOf(rowHeight, block.height).coerceAtLeast(1) / 2) {
            return true
        }
        val center = (rowTop + rowBottom) / 2
        val limit = maxGapY ?: (maxOf(rowHeight, block.height) / 2)
        return kotlin.math.abs(center - block.y) <= limit
    }

    private fun splitIntoSegments(row: List<OcrBlock>, maxGapX: Int?): List<OcrLine> {
        val ordered = row.sortedBy { it.left }
        if (ordered.isEmpty()) return emptyList()
        val lineHeight = (ordered.maxOf { it.bottom } - ordered.minOf { it.top }).coerceAtLeast(1)
        val maxGap = maxGapX ?: maxOf(MIN_JOIN_GAP_PX, (lineHeight * JOIN_GAP_FACTOR).toInt())
        val segments = ArrayList<MutableList<OcrBlock>>()
        var current = mutableListOf(ordered.first())
        segments.add(current)
        var previous = ordered.first()
        for (index in 1 until ordered.size) {
            val block = ordered[index]
            val gap = block.left - previous.right
            if (gap > maxGap) {
                current = mutableListOf(block)
                segments.add(current)
            } else {
                current.add(block)
            }
            previous = block
        }
        return segments.map { segment -> toLine(segment) }
    }

    private fun toLine(blocks: List<OcrBlock>): OcrLine {
        val confidences = blocks.mapNotNull { it.confidence }
        return OcrLine(
            text = blocks.map { it.text }.filter { it.isNotBlank() }.joinToString(" "),
            left = blocks.minOf { it.left },
            top = blocks.minOf { it.top },
            right = blocks.maxOf { it.right },
            bottom = blocks.maxOf { it.bottom },
            confidence = confidences.takeIf { it.isNotEmpty() }?.average(),
            blockCount = blocks.size,
        )
    }

    private const val JOIN_GAP_FACTOR = 2.0
    private const val MIN_JOIN_GAP_PX = 24
}

/**
 * OCR 文本匹配：先做空白归一化的精确包含匹配，再按编辑距离做保守模糊匹配。
 *
 * 模糊匹配只在精确匹配为空时使用，并在结果里标记，调用方可自行决定是否信任。
 */
object OcrMatcher {

    fun match(
        lines: List<OcrLine>,
        query: String,
        fuzzy: Boolean = true,
        minConfidence: Double = 0.0,
    ): List<OcrLineMatch> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isEmpty()) return emptyList()
        val candidates = lines.filter { line ->
            val confidence = line.confidence
            confidence == null || confidence >= minConfidence
        }
        val exact = candidates.filter { normalize(it.text).contains(normalizedQuery) }
        if (exact.isNotEmpty()) {
            return exact.map { OcrLineMatch(it) }
        }
        if (!fuzzy) return emptyList()
        val fuzzyMatches = ArrayList<OcrLineMatch>()
        for (line in candidates) {
            val best = bestDistance(normalizedQuery, normalize(line.text)) ?: continue
            if (best <= maxDistance(normalizedQuery.length)) {
                val similarity = 1.0 - best.toDouble() / normalizedQuery.length
                fuzzyMatches.add(OcrLineMatch(line, fuzzy = true, similarity = similarity))
            }
        }
        return fuzzyMatches
    }

    fun normalize(text: String): String = text
        .lowercase()
        .replace(WHITESPACE, "")

    private fun maxDistance(length: Int): Int = when {
        length <= 1 -> 0
        length <= 3 -> 1
        length <= 6 -> 1
        length <= 12 -> 2
        else -> maxOf(2, length / 6)
    }

    /** 返回 query 与 text 中任一窗口的最小编辑距离；text 过短时直接比较全文。 */
    private fun bestDistance(query: String, text: String): Int? {
        if (text.isEmpty()) return null
        if (text.length <= query.length) {
            return if (query.length - text.length > maxDistance(query.length)) null
            else distance(query, text)
        }
        val minLength = (query.length - maxDistance(query.length)).coerceAtLeast(1)
        val maxLength = (query.length + maxDistance(query.length)).coerceAtMost(text.length)
        var best = Int.MAX_VALUE
        for (length in minLength..maxLength) {
            for (start in 0..text.length - length) {
                val candidate = text.substring(start, start + length)
                val current = distance(query, candidate)
                if (current < best) best = current
                if (best == 0) return 0
            }
        }
        return best.takeIf { it != Int.MAX_VALUE }
    }

    private fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private val WHITESPACE = Regex("\\s+")
}
