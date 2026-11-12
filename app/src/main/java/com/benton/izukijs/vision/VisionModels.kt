package com.benton.izukijs.vision

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** 屏幕像素坐标的包围盒，右/下边界为开区间。 */
data class Box(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)

    val height: Int get() = (bottom - top).coerceAtLeast(0)

    val centerX: Int get() = (left + right) / 2

    val centerY: Int get() = (top + bottom) / 2

    val area: Long get() = width.toLong() * height

    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom

    fun contains(point: PointLike): Boolean = contains(point.x.toInt(), point.y.toInt())

    fun intersects(other: Box): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun intersect(other: Box): Box? {
        val l = max(left, other.left)
        val t = max(top, other.top)
        val r = min(right, other.right)
        val b = min(bottom, other.bottom)
        if (r <= l || b <= t) return null
        return Box(l, t, r, b)
    }

    companion object {
        fun of(x: Int, y: Int, width: Int, height: Int): Box =
            Box(x, y, x + width.coerceAtLeast(0), y + height.coerceAtLeast(0))
    }
}

/** 与 OpenCV Point 解耦的最小点接口，便于纯 JVM 测试。 */
interface PointLike {
    val x: Double
    val y: Double
}

enum class ResultOrderBy {
    Horizontal,
    Vertical,
    Score,
    Area,
    Length,
    Random,
    Expected,
}

/**
 * 统一识别结果。
 *
 * - [box] 为屏幕像素包围盒；
 * - [score] 对模板匹配是相似度、对找色/特征是像素或特征点数量、对 OCR 是置信度；
 * - [count] 仅找色/特征匹配使用；
 * - [text] 仅 OCR 使用；
 * - [clsIndex]/[label] 仅神经网络使用。
 */
data class VisionResult(
    val box: Box,
    val score: Double = 0.0,
    val count: Int = 0,
    val text: String? = null,
    val clsIndex: Int = -1,
    val label: String? = null,
)

class VisionSpecException(message: String) : Exception(message)

/** 与 Python 一致的负数下标：-1 表示最后一个。 */
fun pythonicIndex(total: Int, index: Int): Int? {
    if (index >= 0 && index < total) return index
    if (index < 0 && -index.toLong() <= total.toLong()) return total + index
    return null
}

fun parseOrderBy(raw: String?, default: ResultOrderBy): ResultOrderBy = when (raw?.trim()?.lowercase(java.util.Locale.ROOT)) {
    null, "" -> default
    "horizontal" -> ResultOrderBy.Horizontal
    "vertical" -> ResultOrderBy.Vertical
    "score" -> ResultOrderBy.Score
    "area" -> ResultOrderBy.Area
    "length" -> ResultOrderBy.Length
    "random" -> ResultOrderBy.Random
    "expected" -> ResultOrderBy.Expected
    else -> default
}

/**
 * 按 [orderBy] 排序；`Expected` 优先按 [expectedText] 正则顺序，其次按 [expectedIndices] 分类下标。
 */
fun orderResults(
    results: List<VisionResult>,
    orderBy: ResultOrderBy,
    expectedText: List<Regex> = emptyList(),
    expectedIndices: List<Int> = emptyList(),
): List<VisionResult> {
    if (results.size <= 1) return results
    return when (orderBy) {
        ResultOrderBy.Horizontal -> results.sortedWith(compareBy({ it.box.left }, { it.box.top }))
        ResultOrderBy.Vertical -> results.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        ResultOrderBy.Score -> results.sortedByDescending { it.score }
        ResultOrderBy.Area -> results.sortedByDescending { it.box.area }
        ResultOrderBy.Length -> results.sortedByDescending { it.text?.length ?: 0 }
        ResultOrderBy.Random -> results.shuffled(Random.Default)
        ResultOrderBy.Expected -> when {
            expectedText.isNotEmpty() -> {
                results
                    .map { result -> result to expectedText.indexOfFirst { it.containsMatchIn(result.text.orEmpty()) } }
                    .sortedWith(
                        compareBy(
                            { if (it.second < 0) Int.MAX_VALUE else it.second },
                            { it.first.box.top },
                            { it.first.box.left },
                        ),
                    )
                    .map { it.first }
            }

            expectedIndices.isNotEmpty() -> {
                results
                    .map { result -> result to expectedIndices.indexOf(result.clsIndex) }
                    .sortedWith(
                        compareBy(
                            { if (it.second < 0) Int.MAX_VALUE else it.second },
                            { it.first.box.top },
                            { it.first.box.left },
                        ),
                    )
                    .map { it.first }
            }

            else -> results.sortedWith(compareBy({ it.box.left }, { it.box.top }))
        }
    }
}

fun selectIndex(results: List<VisionResult>, index: Int): VisionResult? =
    pythonicIndex(results.size, index)?.let(results::get)

fun intersectionArea(a: Box, b: Box): Long {
    val l = max(a.left, b.left)
    val t = max(a.top, b.top)
    val r = min(a.right, b.right)
    val btm = min(a.bottom, b.bottom)
    if (r <= l || btm <= t) return 0
    return (r - l).toLong() * (btm - t).toLong()
}

/**
 * 模板匹配 NMS：交集覆盖候选框 [threshold] 以上即抑制。
 * 与 MaaFramework 的 `NMS` 语义一致（比较的是被抑制框自身面积）。
 */
fun nmsByScore(
    results: List<VisionResult>,
    threshold: Double = 0.2,
    minScore: Double = 0.0,
): List<VisionResult> {
    if (results.size <= 1) return results
    val order = results.indices.sortedByDescending { results[it].score }
    val suppressed = BooleanArray(results.size)
    val out = ArrayList<VisionResult>(results.size)
    for (i in order) {
        if (suppressed[i]) continue
        val first = results[i]
        if (first.score < minScore) continue
        out.add(first)
        for (j in order) {
            if (j == i || suppressed[j]) continue
            val candidate = results[j]
            val area = candidate.box.area
            if (area <= 0) continue
            if (intersectionArea(first.box, candidate.box) >= threshold * area) suppressed[j] = true
        }
    }
    return out
}

/** 找色/特征匹配的按数量 NMS。 */
fun nmsByCount(results: List<VisionResult>, threshold: Double = 0.7): List<VisionResult> {
    if (results.size <= 1) return results
    val order = results.indices.sortedByDescending { results[it].count }
    val suppressed = BooleanArray(results.size)
    val out = ArrayList<VisionResult>(results.size)
    for (i in order) {
        if (suppressed[i]) continue
        val first = results[i]
        if (first.count <= 0) continue
        out.add(first)
        for (j in order) {
            if (j == i || suppressed[j]) continue
            val candidate = results[j]
            val area = candidate.box.area
            if (area <= 0) continue
            if (intersectionArea(first.box, candidate.box) >= threshold * area) suppressed[j] = true
        }
    }
    return out
}
