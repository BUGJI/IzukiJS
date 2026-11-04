package com.benton.izukijs.model

import org.json.JSONArray
import org.json.JSONObject

/** 图案轨迹中的一个点，坐标与时间均相对于屏幕 / 所属笔画。 */
data class PatternPoint(val x: Float, val y: Float, val timeMs: Long = 0L)

/** 一次从按下到抬起的完整触摸笔画；[startTimeMs] 相对于整段录制开始。 */
data class UnlockStroke(
    val points: List<PatternPoint>,
    val startTimeMs: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * 锁屏录制结果。坐标归一化到整块屏幕（0..1），可包含多段笔画：
 * 图案通常是一段连续笔画，PIN / 点击解锁则可能是多次独立点击。
 */
data class UnlockPattern(
    val version: Int = VERSION,
    val rotation: Int,
    val strokes: List<UnlockStroke>,
    /** 录制时的逻辑屏幕尺寸；旧版本数据为 0，仅作缩放/诊断用。 */
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
) {
    fun toJson(): String = JSONObject().apply {
        put("version", version)
        put("rotation", rotation)
        put("screenWidth", screenWidth)
        put("screenHeight", screenHeight)
        put(
            "strokes",
            JSONArray().apply {
                strokes.forEach { stroke ->
                    put(
                        JSONObject().apply {
                            put("startTimeMs", stroke.startTimeMs)
                            put("durationMs", stroke.durationMs)
                            put(
                                "points",
                                JSONArray().apply {
                                    stroke.points.forEach { point ->
                                        put(
                                            JSONObject().apply {
                                                put("x", point.x.toDouble())
                                                put("y", point.y.toDouble())
                                                put("timeMs", point.timeMs)
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
    }.toString()

    companion object {
        const val VERSION = 1

        fun parseOrNull(raw: String): UnlockPattern? {
            if (raw.isBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                if (json.optInt("version", 0) != VERSION) return@runCatching null
                val strokes = json.optJSONArray("strokes")?.let { parseStrokes(it) }
                    ?: json.optJSONArray("points")?.let { legacyStroke(it) }
                    ?: return@runCatching null
                if (strokes.isEmpty()) null else UnlockPattern(
                    rotation = json.optInt("rotation", 0),
                    strokes = strokes,
                    screenWidth = json.optInt("screenWidth", 0).coerceAtLeast(0),
                    screenHeight = json.optInt("screenHeight", 0).coerceAtLeast(0),
                )
            }.getOrNull()
        }

        private fun parseStrokes(array: JSONArray): List<UnlockStroke> = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val points = parsePoints(item.optJSONArray("points")) ?: continue
                if (points.isEmpty()) continue
                add(
                    UnlockStroke(
                        points = points,
                        startTimeMs = item.optLong("startTimeMs", 0L).coerceAtLeast(0L),
                        durationMs = item.optLong("durationMs", 0L).coerceAtLeast(0L),
                    ),
                )
            }
        }

        private fun legacyStroke(array: JSONArray): List<UnlockStroke> {
            val points = parsePoints(array) ?: return emptyList()
            if (points.isEmpty()) return emptyList()
            return listOf(UnlockStroke(points = points))
        }

        private fun parsePoints(array: JSONArray?): List<PatternPoint>? {
            if (array == null) return null
            return buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val x = item.optDouble("x", Double.NaN)
                    val y = item.optDouble("y", Double.NaN)
                    if (!x.isFinite() || !y.isFinite()) continue
                    add(
                        PatternPoint(
                            x = x.toFloat().coerceIn(0f, 1f),
                            y = y.toFloat().coerceIn(0f, 1f),
                            timeMs = item.optLong("timeMs", 0L).coerceAtLeast(0L),
                        ),
                    )
                }
            }
        }
    }
}
