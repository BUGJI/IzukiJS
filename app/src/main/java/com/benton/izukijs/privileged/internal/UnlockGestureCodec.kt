package com.benton.izukijs.privileged.internal

import com.benton.izukijs.model.PatternPoint
import com.benton.izukijs.model.UnlockPattern
import com.benton.izukijs.model.UnlockStroke
import kotlin.math.roundToInt

/** 原始（已映射到逻辑屏幕的）轨迹 -> 归一化 UnlockPattern。 */
internal object UnlockGestureCodec {

    private const val TAP_MOVE_TOLERANCE_PX = 20
    private const val MIN_POINT_INTERVAL_MS = 8
    private const val SHARP_MOVE_PX = 16
    private const val MAX_POINTS_PER_STROKE = 400
    private const val MAX_STROKES = 64

    fun build(strokes: List<TouchStroke>, screen: ScreenGeometry): UnlockPattern {
        val baseTMs = strokes.firstOrNull()?.points?.firstOrNull()?.tMs ?: 0
        return UnlockPattern(
            rotation = screen.rotation,
            screenWidth = screen.width,
            screenHeight = screen.height,
            strokes = strokes.take(MAX_STROKES).mapNotNull { stroke ->
                toStroke(stroke, screen, baseTMs)
            },
        )
    }

    private fun toStroke(
        stroke: TouchStroke,
        screen: ScreenGeometry,
        baseTMs: Int,
    ): UnlockStroke? {
        val points = stroke.points
        if (points.isEmpty()) return null
        val startTMs = points.first().tMs
        val first = points.first()
        val moved = points.any {
            val dx = it.x - first.x
            val dy = it.y - first.y
            dx.toLong() * dx + dy.toLong() * dy >
                TAP_MOVE_TOLERANCE_PX.toLong() * TAP_MOVE_TOLERANCE_PX
        }
        val sampled = if (moved) decimate(points) else listOf(first)
        if (sampled.isEmpty()) return null

        val normalized = sampled.map { point ->
            PatternPoint(
                x = (point.x.toFloat() / screen.width).coerceIn(0f, 1f),
                y = (point.y.toFloat() / screen.height).coerceIn(0f, 1f),
                timeMs = (point.tMs - startTMs).toLong().coerceAtLeast(0L),
            )
        }
        val duration = (stroke.endTMs - startTMs).toLong().coerceAtLeast(1L)
        return UnlockStroke(
            points = normalized,
            startTimeMs = (startTMs - baseTMs).toLong().coerceAtLeast(0L),
            durationMs = duration,
        )
    }

    private fun decimate(points: List<TouchPoint>): List<TouchPoint> {
        val kept = mutableListOf<TouchPoint>()
        for ((index, point) in points.withIndex()) {
            val last = kept.lastOrNull()
            val keep = last == null ||
                index == points.lastIndex ||
                point.tMs - last.tMs >= MIN_POINT_INTERVAL_MS ||
                distanceSquared(point, last) >= (SHARP_MOVE_PX - 1) * (SHARP_MOVE_PX - 1)
            if (keep) kept += point
        }
        return if (kept.size > MAX_POINTS_PER_STROKE) resample(kept) else kept
    }

    private fun resample(points: List<TouchPoint>): List<TouchPoint> {
        val out = ArrayList<TouchPoint>(MAX_POINTS_PER_STROKE)
        val step = (points.size - 1).toDouble() / (MAX_POINTS_PER_STROKE - 1)
        for (i in 0 until MAX_POINTS_PER_STROKE - 1) {
            out += points[(i * step).roundToInt().coerceAtMost(points.lastIndex)]
        }
        out += points.last()
        return out
    }

    private fun distanceSquared(a: TouchPoint, b: TouchPoint): Int {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }
}
