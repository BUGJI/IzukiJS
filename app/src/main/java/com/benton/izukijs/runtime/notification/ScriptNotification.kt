package com.benton.izukijs.runtime.notification

import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** 进度条中的一个分段；[length] 与 [ScriptNotificationState.max] 使用同一单位。 */
data class ProgressSegment(val length: Int, val color: Int? = null)

/** 进度条上的一个节点；[position] 必须在 `0..max` 内。 */
data class ProgressPoint(val position: Int, val color: Int? = null)

/**
 * 脚本运行通知的完整状态。脚本通过 `notify.progress` / `notify.update` 修改，
 * 前台服务监听该状态并重建通知。
 */
data class ScriptNotificationState(
    val active: Boolean = false,
    val scriptName: String = "",
    val title: String = "",
    val text: String = "",
    val stage: String? = null,
    val value: Int = 0,
    val max: Int = DEFAULT_PROGRESS_MAX,
    val indeterminate: Boolean = true,
    val color: Int? = null,
    val segments: List<ProgressSegment> = emptyList(),
    val points: List<ProgressPoint> = emptyList(),
    val shortCriticalText: String? = null,
    val trackerIcon: String? = null,
    val startIcon: String? = null,
    val endIcon: String? = null,
    val silent: Boolean = true,
    val colorized: Boolean = false,
    val visibility: Int = NotificationCompat.VISIBILITY_PUBLIC,
    val timeoutMs: Long = 0L,
    val showStopAction: Boolean = true,
    val promoted: Boolean = true,
) {
    companion object {
        const val DEFAULT_PROGRESS_MAX = 100
    }
}

/**
 * 从脚本 JSON 解析出的通知选项。使用 [present] 区分「未提供」和「显式设为空」，
 * 从而支持 `notify.update({ stage: null })` 这类局部更新。
 */
data class ScriptNotificationOptions(
    val present: Set<String> = emptySet(),
    val title: String? = null,
    val text: String? = null,
    val stage: String? = null,
    val value: Int? = null,
    val max: Int? = null,
    val indeterminate: Boolean? = null,
    val color: Int? = null,
    val segments: List<ProgressSegment>? = null,
    val points: List<ProgressPoint>? = null,
    val shortCriticalText: String? = null,
    val trackerIcon: String? = null,
    val startIcon: String? = null,
    val endIcon: String? = null,
    val silent: Boolean? = null,
    val colorized: Boolean? = null,
    val visibility: Int? = null,
    val timeoutMs: Long? = null,
    val showStopAction: Boolean? = null,
    val promoted: Boolean? = null,
) {
    companion object {
        private val ICON_KEYS = setOf("script", "progress", "none")

        fun parse(json: String): ScriptNotificationOptions? = runCatching {
            val obj = JSONObject(json)
            val present = obj.keys().asSequence().toSet()
            ScriptNotificationOptions(
                present = present,
                title = obj.optStringOrNull("title"),
                text = obj.optStringOrNull("text"),
                stage = obj.optStringOrNull("stage"),
                value = obj.optIntOrNull("value"),
                max = obj.optIntOrNull("max"),
                indeterminate = obj.optBooleanOrNull("indeterminate"),
                color = obj.optColorOrNull("color"),
                segments = obj.optSegmentsOrNull("segments"),
                points = obj.optPointsOrNull("points"),
                shortCriticalText = obj.optStringOrNull("shortCriticalText"),
                trackerIcon = obj.optIconOrNull("trackerIcon"),
                startIcon = obj.optIconOrNull("startIcon"),
                endIcon = obj.optIconOrNull("endIcon"),
                silent = obj.optBooleanOrNull("silent"),
                colorized = obj.optBooleanOrNull("colorized"),
                visibility = obj.optVisibilityOrNull("visibility"),
                timeoutMs = obj.optLongOrNull("timeoutMs")?.coerceAtLeast(0L),
                showStopAction = obj.optBooleanOrNull("showStopAction"),
                promoted = obj.optBooleanOrNull("promoted"),
            )
        }.getOrNull()

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (!has(key) || isNull(key)) null else optString(key)

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (!has(key) || isNull(key)) null else optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }

        private fun JSONObject.optLongOrNull(key: String): Long? =
            if (!has(key) || isNull(key)) null else optLong(key, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }

        private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
            if (!has(key) || isNull(key)) null else optBoolean(key)

        private fun JSONObject.optColorOrNull(key: String): Int? =
            if (!has(key) || isNull(key)) return null else parseColor(optString(key))

        private fun JSONObject.optIconOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return optString(key).trim().lowercase().takeIf { it in ICON_KEYS }
        }

        private fun JSONObject.optVisibilityOrNull(key: String): Int? {
            if (!has(key) || isNull(key)) return null
            return when (optString(key).trim().lowercase()) {
                "public" -> NotificationCompat.VISIBILITY_PUBLIC
                "private" -> NotificationCompat.VISIBILITY_PRIVATE
                "secret" -> NotificationCompat.VISIBILITY_SECRET
                else -> null
            }
        }

        private fun JSONObject.optSegmentsOrNull(key: String): List<ProgressSegment>? {
            if (!has(key)) return null
            val array = optJSONArray(key) ?: return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val length = item.optInt("length", -1)
                    if (length <= 0) continue
                    val color = if (item.has("color")) parseColor(item.optString("color")) else null
                    add(ProgressSegment(length, color))
                }
            }
        }

        private fun JSONObject.optPointsOrNull(key: String): List<ProgressPoint>? {
            if (!has(key)) return null
            val array = optJSONArray(key) ?: return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val position = item.optInt("position", -1)
                    if (position <= 0) continue
                    val color = if (item.has("color")) parseColor(item.optString("color")) else null
                    add(ProgressPoint(position, color))
                }
            }
        }

        /** 解析 `#RGB` / `#ARGB` / `#RRGGBB` / `#AARRGGBB`，失败返回 null。 */
        fun parseColor(raw: String?): Int? {
            val value = raw?.trim()?.removePrefix("#") ?: return null
            if (value.length !in setOf(3, 4, 6, 8)) return null
            val expanded = when (value.length) {
                3, 4 -> buildString {
                    value.forEach { append(it).append(it) }
                }

                else -> value
            }
            val argb = when (expanded.length) {
                6 -> "ff$expanded"
                8 -> expanded
                else -> return null
            }
            return argb.toLongOrNull(16)?.toInt()
        }
    }
}

/** 把选项合并进当前状态；[allowProgress] 为 false 时忽略所有进度相关字段。 */
fun ScriptNotificationState.applyOptions(
    options: ScriptNotificationOptions,
    allowProgress: Boolean,
): ScriptNotificationState {
    var next = this
    if ("title" in options.present) next = next.copy(title = options.title.orEmpty())
    if ("text" in options.present) next = next.copy(text = options.text.orEmpty())
    if ("stage" in options.present) next = next.copy(stage = options.stage?.takeIf { it.isNotBlank() })
    if ("shortCriticalText" in options.present) {
        next = next.copy(shortCriticalText = options.shortCriticalText?.takeIf { it.isNotBlank() })
    }
    if ("silent" in options.present) next = next.copy(silent = options.silent ?: next.silent)
    if ("colorized" in options.present) next = next.copy(colorized = options.colorized ?: next.colorized)
    if ("visibility" in options.present) next = next.copy(visibility = options.visibility ?: next.visibility)
    if ("timeoutMs" in options.present) next = next.copy(timeoutMs = options.timeoutMs ?: 0L)
    if ("showStopAction" in options.present) {
        next = next.copy(showStopAction = options.showStopAction ?: next.showStopAction)
    }
    if ("promoted" in options.present) next = next.copy(promoted = options.promoted ?: next.promoted)
    if ("trackerIcon" in options.present) next = next.copy(trackerIcon = options.trackerIcon)
    if ("startIcon" in options.present) next = next.copy(startIcon = options.startIcon)
    if ("endIcon" in options.present) next = next.copy(endIcon = options.endIcon)

    if (!allowProgress) return next

    if ("color" in options.present) next = next.copy(color = options.color)
    if ("segments" in options.present) next = next.copy(segments = options.segments.orEmpty())
    if ("points" in options.present) next = next.copy(points = options.points.orEmpty())
    var forcedIndeterminate = false
    if ("max" in options.present) {
        val max = options.max ?: ScriptNotificationState.DEFAULT_PROGRESS_MAX
        if (max <= 0) {
            forcedIndeterminate = true
            next = next.copy(max = ScriptNotificationState.DEFAULT_PROGRESS_MAX, indeterminate = true)
        } else {
            next = next.copy(max = max)
        }
    }
    if ("value" in options.present) next = next.copy(value = options.value ?: 0)
    if ("indeterminate" in options.present) {
        next = next.copy(indeterminate = options.indeterminate ?: false)
    } else if (!forcedIndeterminate && ("value" in options.present || "max" in options.present)) {
        next = next.copy(indeterminate = false)
    }

    if (next.segments.isNotEmpty()) {
        val segmentMax = next.segments.sumOf { it.length }
        if (segmentMax > 0) next = next.copy(max = segmentMax)
    }
    val max = next.max.coerceAtLeast(1)
    val points = next.points
        .map { it.copy(position = it.position.coerceIn(1, max)) }
        .distinctBy { it.position }
    return next.copy(
        value = next.value.coerceIn(0, max),
        points = points,
    )
}

/** 进程内通知状态中心；前台服务监听它，脚本 API 写入它。 */
object ScriptNotificationCenter {

    private val _state = MutableStateFlow(ScriptNotificationState())
    val state: StateFlow<ScriptNotificationState> = _state.asStateFlow()

    val isActive: Boolean get() = _state.value.active

    fun begin(scriptName: String) {
        _state.value = ScriptNotificationState(
            active = true,
            scriptName = scriptName,
            text = scriptName,
        )
    }

    fun update(json: String, allowProgress: Boolean): Boolean {
        val options = ScriptNotificationOptions.parse(json) ?: return false
        return update(options, allowProgress)
    }

    fun update(options: ScriptNotificationOptions, allowProgress: Boolean): Boolean {
        if (!_state.value.active) return false
        _state.update { it.applyOptions(options, allowProgress) }
        return true
    }

    fun reset() {
        _state.update {
            it.copy(
                value = 0,
                max = ScriptNotificationState.DEFAULT_PROGRESS_MAX,
                indeterminate = true,
                color = null,
                segments = emptyList(),
                points = emptyList(),
                shortCriticalText = null,
                trackerIcon = null,
                startIcon = null,
                endIcon = null,
            )
        }
    }

    fun clear() {
        _state.value = ScriptNotificationState()
    }
}
