package com.benton.izukijs.controller.record

import org.json.JSONObject

/**
 * 录制时间线的行生成与分页。
 *
 * 默认只输出动作 / 页面切换，避免长时间录制被每 700ms 一条的截图行淹没；
 * 需要画面时由调用方显式把 `screencap` 加入 [DEFAULT_TYPES] 之外的类型集合。
 */
object RecordingTimeline {

    const val TYPE_SCREENCAP = "screencap"

    /** 默认时间线类型：动作、脚本动作与页面切换，不含周期截图。 */
    val DEFAULT_TYPES: Set<String> = linkedSetOf(
        "tap",
        "long_click",
        "swipe",
        "gesture",
        "multi_swipe",
        "key",
        "click",
        "text",
        "global_action",
        "shell",
        "app",
    )

    data class Page(
        val items: List<JSONObject>,
        val total: Int,
        val offset: Int,
        val hasMore: Boolean,
        val nextOffset: Int?,
    )

    fun page(
        events: List<RecordEvent>,
        sinceMs: Long = 0L,
        types: Set<String>? = DEFAULT_TYPES,
        offset: Int = 0,
        limit: Int = 200,
    ): Page {
        val rows = ArrayList<JSONObject>()
        events.forEach { event ->
            if (event.timestamp < sinceMs) return@forEach
            val row = row(event) ?: return@forEach
            if (types != null && row.optString("type") !in types) return@forEach
            rows.add(row)
        }
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceAtLeast(1)
        val page = rows.drop(safeOffset).take(safeLimit)
        val consumed = safeOffset + page.size
        return Page(
            items = page,
            total = rows.size,
            offset = safeOffset,
            hasMore = consumed < rows.size,
            nextOffset = consumed.takeIf { consumed < rows.size },
        )
    }

    /** 把单个事件转成紧凑行；会话头与 context 不占时间线行。 */
    fun row(event: RecordEvent): JSONObject? = when (event) {
        is UserActionEvent -> JSONObject().apply {
            put("t", event.timestamp)
            put("source", "user")
            put("type", event.kind.jsonValue)
            if (event.kind == UserActionKind.TAP || event.kind == UserActionKind.LONG_CLICK) {
                put("x", event.x)
                put("y", event.y)
                put("nx", event.nx)
                put("ny", event.ny)
            } else {
                put("x1", event.x)
                put("y1", event.y)
                put("x2", event.x2)
                put("y2", event.y2)
                put("nx1", event.nx)
                put("ny1", event.ny)
                put("nx2", event.nx2)
                put("ny2", event.ny2)
            }
            put("durationMs", event.durationMs)
            if (event.pointerCount > 1) put("pointerCount", event.pointerCount)
            if (event.packageName.isNotBlank()) put("package", event.packageName)
            event.targets.firstOrNull()?.let { put("target", it.toJson()) }
            // frame 保持旧字段；before_frame / after_frame 便于调用方同时看到动作前后画面。
            event.beforeFrame?.let {
                put("frame", it)
                put("before_frame", it)
            }
            event.afterFrame?.let { put("after_frame", it) }
        }

        is ClickEvent -> JSONObject().put("t", event.timestamp).put("source", event.source)
            .put("type", "click").put("x", event.x).put("y", event.y)
        is LongClickEvent -> JSONObject().put("t", event.timestamp).put("source", event.source)
            .put("type", "long_click").put("x", event.x).put("y", event.y)
        is SwipeEvent -> JSONObject().put("t", event.timestamp).put("source", event.source)
            .put("type", "swipe").put("x1", event.x1).put("y1", event.y1)
            .put("x2", event.x2).put("y2", event.y2)
        is GestureEvent -> JSONObject().put("t", event.timestamp).put("source", event.source)
            .put("type", "gesture")
        is PressKeyEvent -> JSONObject().put("t", event.timestamp).put("source", event.source)
            .put("type", "key").put("keyCode", event.keyCode)
        is InputTextEvent -> JSONObject().put("t", event.timestamp).put("source", event.source)
            .put("type", "text").put("text", event.text)
        is GlobalActionEvent -> JSONObject().put("t", event.timestamp).put("source", event.source)
            .put("type", "global_action").put("action", event.action)
        is AppEvent -> JSONObject().put("t", event.timestamp).put("source", event.source)
            .put("type", "app").put("package", event.packageName).put("action", event.action)
        is ShellEvent -> JSONObject().put("t", event.timestamp).put("source", event.source)
            .put("type", "shell").put("cmd", event.cmd)
        is ScreenshotEvent -> JSONObject().put("t", event.timestamp)
            .put("type", TYPE_SCREENCAP).put("frame", event.frame)
            .put("w", event.width).put("h", event.height)
            .apply { if (event.stale) put("stale", true) }
        // context 已合并回动作；session_start 不是时间线动作。
        is SessionStartEvent, is ContextEvent -> null
    }
}
