package com.benton.izukijs.controller.record

import org.json.JSONArray
import org.json.JSONObject

enum class RecordActionKind {
    CLICK,
    LONG_CLICK,
    SWIPE,
    GESTURE,
    PRESS_KEY,
    INPUT_TEXT,
    GLOBAL_ACTION,
    SHELL,
    APP,
}

/** 录制事件来源：用户真实触摸 / 脚本 / MCP / 录制系统本身。 */
object RecordSource {
    const val USER = "user"
    const val SCRIPT = "script"
    const val MCP = "mcp"
    const val SYSTEM = "system"

    fun normalize(value: String?): String = when (value) {
        USER, SCRIPT, MCP, SYSTEM -> value
        else -> SCRIPT
    }
}

/** 用户真实触摸动作类型。 */
enum class UserActionKind(val jsonValue: String) {
    TAP("tap"),
    LONG_CLICK("long_click"),
    SWIPE("swipe"),
    GESTURE("gesture"),
    MULTI_SWIPE("multi_swipe"),
    KEY("key"),
    ;

    companion object {
        fun from(value: String?): UserActionKind? =
            entries.firstOrNull { it.jsonValue == value }
    }
}

/** 录制文件格式版本。 */
object RecordFormat {
    const val VERSION = 2

    const val TYPE_SCREENSHOT = "screencap"
    const val TYPE_SESSION_START = "session_start"
    const val TYPE_CONTEXT = "context"
}

/** 点击命中的控件节点（从最内层向外，最多保留 3 层）。 */
data class UiTarget(
    val className: String?,
    val text: String?,
    val viewId: String?,
    val contentDescription: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val editable: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        className?.let { put("class", it) }
        text?.takeIf { it.isNotBlank() }?.let { put("text", it) }
        viewId?.takeIf { it.isNotBlank() }?.let { put("id", it) }
        contentDescription?.takeIf { it.isNotBlank() }?.let { put("desc", it) }
        put("bounds", JSONArray(listOf(left, top, right, bottom)))
        if (clickable) put("clickable", true)
        if (editable) put("editable", true)
    }

    companion object {
        fun fromJson(json: JSONObject): UiTarget = UiTarget(
            className = json.optString("class").takeIf { it.isNotBlank() },
            text = json.optString("text").takeIf { it.isNotBlank() },
            viewId = json.optString("id").takeIf { it.isNotBlank() },
            contentDescription = json.optString("desc").takeIf { it.isNotBlank() },
            left = json.optJSONArray("bounds")?.optInt(0) ?: 0,
            top = json.optJSONArray("bounds")?.optInt(1) ?: 0,
            right = json.optJSONArray("bounds")?.optInt(2) ?: 0,
            bottom = json.optJSONArray("bounds")?.optInt(3) ?: 0,
            clickable = json.optBoolean("clickable"),
            editable = json.optBoolean("editable"),
        )

        fun listToJson(targets: List<UiTarget>): JSONArray = JSONArray().apply {
            targets.forEach { put(it.toJson()) }
        }

        fun listFromJson(array: JSONArray?): List<UiTarget> {
            if (array == null) return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { add(fromJson(it)) }
                }
            }
        }
    }
}

/**
 * 录制事件基类。
 *
 * v2 字段语义：
 * - [timestamp] / `atMs`：动作或截图**开始**的录制内相对时间；
 * - [elapsedMs]：动作或截图耗时；
 * - [seq]：单写入线程分配的单调序号，用于打破同毫秒事件的顺序歧义；
 * - [source]：user / script / mcp / system；
 * - [ok]：动作是否成功。
 *
 * 旧版只有 `timestamp`，读取时由 [RecordEvent.fromJson] 自动补齐。
 */
sealed interface RecordEvent {
    val timestamp: Long
    val seq: Long get() = 0L
    val elapsedMs: Long get() = 0L
    val source: String get() = RecordSource.SCRIPT
    val ok: Boolean get() = true

    fun toJson(): JSONObject

    companion object {
        internal fun baseJson(
            event: RecordEvent,
            type: String,
            source: String = event.source,
            ok: Boolean = event.ok,
        ): JSONObject = JSONObject()
            .put("v", RecordFormat.VERSION)
            .put("type", type)
            .put("atMs", event.timestamp)
            .put("timestamp", event.timestamp)
            .put("seq", event.seq)
            .put("elapsedMs", event.elapsedMs)
            .put("source", source)
            .put("ok", ok)
            .put("success", ok)

        fun fromJson(json: JSONObject): RecordEvent? {
            val atMs = if (json.has("atMs")) json.optLong("atMs") else json.optLong("timestamp")
            val seq = json.optLong("seq")
            val elapsed = json.optLong("elapsedMs")
            val source = RecordSource.normalize(json.optString("source"))
            val ok = if (json.has("ok")) json.optBoolean("ok") else json.optBoolean("success", true)
            return when (json.optString("type")) {
                RecordFormat.TYPE_SESSION_START, "session" -> SessionStartEvent.fromJson(json, atMs, seq)

                RecordFormat.TYPE_SCREENSHOT, "screenshot" -> ScreenshotEvent(
                    timestamp = atMs,
                    frame = json.optString("frame"),
                    width = json.optInt("width"),
                    height = json.optInt("height"),
                    stale = json.optBoolean("stale", false),
                    source = source,
                    seq = seq,
                    elapsedMs = elapsed,
                )

                "click" -> ClickEvent(
                    timestamp = atMs,
                    x = json.optInt("x"),
                    y = json.optInt("y"),
                    success = ok,
                    source = source,
                    seq = seq,
                    elapsedMs = elapsed,
                )

                "long_click" -> LongClickEvent(
                    timestamp = atMs,
                    x = json.optInt("x"),
                    y = json.optInt("y"),
                    durationMs = json.optLong("durationMs"),
                    success = ok,
                    source = source,
                    seq = seq,
                    elapsedMs = elapsed,
                )

                "swipe" -> SwipeEvent(
                    timestamp = atMs,
                    x1 = json.optInt("x1"),
                    y1 = json.optInt("y1"),
                    x2 = json.optInt("x2"),
                    y2 = json.optInt("y2"),
                    durationMs = json.optLong("durationMs"),
                    success = ok,
                    source = source,
                    seq = seq,
                    elapsedMs = elapsed,
                )

                "gesture" -> GestureEvent(
                    timestamp = atMs,
                    strokes = json.optJSONArray("strokes") ?: JSONArray(),
                    success = ok,
                    source = source,
                    seq = seq,
                    elapsedMs = elapsed,
                )

                "press_key" -> PressKeyEvent(
                    timestamp = atMs,
                    keyCode = json.optInt("keyCode"),
                    success = ok,
                    source = source,
                    seq = seq,
                    elapsedMs = elapsed,
                )

                "input_text" -> InputTextEvent(
                    timestamp = atMs,
                    text = json.optString("text"),
                    method = json.optString("method"),
                    success = ok,
                    source = source,
                    seq = seq,
                    elapsedMs = elapsed,
                )

                "global_action" -> GlobalActionEvent(
                    timestamp = atMs,
                    action = json.optInt("action"),
                    success = ok,
                    source = source,
                    seq = seq,
                    elapsedMs = elapsed,
                )

                "shell" -> ShellEvent(
                    timestamp = atMs,
                    cmd = json.optString("cmd"),
                    stdout = json.optString("stdout"),
                    stderr = json.optString("stderr"),
                    exitCode = json.optInt("exitCode", -1),
                    success = ok,
                    source = source,
                    seq = seq,
                    elapsedMs = elapsed,
                )

                "app" -> AppEvent(
                    timestamp = atMs,
                    action = json.optString("action"),
                    packageName = json.optString("package"),
                    success = ok,
                    source = source,
                    seq = seq,
                    elapsedMs = elapsed,
                )

                "user_action" -> UserActionEvent.fromJson(json, atMs, seq, elapsed, source, ok)

                RecordFormat.TYPE_CONTEXT -> ContextEvent(
                    timestamp = atMs,
                    refSeq = json.optLong("refSeq"),
                    packageName = json.optString("package"),
                    targets = UiTarget.listFromJson(json.optJSONArray("targets")),
                    rotation = json.optInt("rotation"),
                    seq = seq,
                    elapsedMs = elapsed,
                    source = source,
                )

                else -> null
            }
        }

        fun kindOf(event: RecordEvent): RecordActionKind? = when (event) {
            is ClickEvent -> RecordActionKind.CLICK
            is LongClickEvent -> RecordActionKind.LONG_CLICK
            is SwipeEvent -> RecordActionKind.SWIPE
            is GestureEvent -> RecordActionKind.GESTURE
            is PressKeyEvent -> RecordActionKind.PRESS_KEY
            is InputTextEvent -> RecordActionKind.INPUT_TEXT
            is GlobalActionEvent -> RecordActionKind.GLOBAL_ACTION
            is ShellEvent -> RecordActionKind.SHELL
            is AppEvent -> RecordActionKind.APP
            is UserActionEvent, is SessionStartEvent, is ContextEvent, is ScreenshotEvent -> null
        }

        fun successOf(event: RecordEvent): Boolean = event.ok
    }
}

/** 录制会话头：版本、设备、选项。回放前用于校验。 */
data class SessionStartEvent(
    override val timestamp: Long = 0L,
    val appVersion: String = "",
    val device: JSONObject = JSONObject(),
    val options: JSONObject = JSONObject(),
    override val seq: Long = 0L,
    override val elapsedMs: Long = 0L,
    override val source: String = RecordSource.SYSTEM,
) : RecordEvent {
    override fun toJson(): JSONObject = RecordEvent.baseJson(this, RecordFormat.TYPE_SESSION_START).apply {
        put("appVersion", appVersion)
        put("device", device)
        put("options", options)
    }

    companion object {
        fun fromJson(json: JSONObject, atMs: Long, seq: Long): SessionStartEvent = SessionStartEvent(
            timestamp = atMs,
            appVersion = json.optString("appVersion"),
            device = json.optJSONObject("device") ?: JSONObject(),
            options = json.optJSONObject("options") ?: JSONObject(),
            seq = seq,
            elapsedMs = json.optLong("elapsedMs"),
        )
    }
}

/** 异步补全的动作上下文，通过 [refSeq] 关联到具体动作事件。 */
data class ContextEvent(
    override val timestamp: Long,
    val refSeq: Long,
    val packageName: String = "",
    val targets: List<UiTarget> = emptyList(),
    val rotation: Int = 0,
    override val seq: Long = 0L,
    override val elapsedMs: Long = 0L,
    override val source: String = RecordSource.USER,
) : RecordEvent {
    override fun toJson(): JSONObject = RecordEvent.baseJson(this, RecordFormat.TYPE_CONTEXT).apply {
        put("refSeq", refSeq)
        if (packageName.isNotBlank()) put("package", packageName)
        if (targets.isNotEmpty()) put("targets", UiTarget.listToJson(targets))
        if (rotation != 0) put("rotation", rotation)
    }
}

data class ScreenshotEvent(
    override val timestamp: Long,
    val frame: String,
    val width: Int,
    val height: Int,
    /**
     * true 表示 [frame] 复用了上一帧旧图（超过录制帧上限，或画面采样去重）；
     * 消费方（时间线 / MCP / 回放）据此避免把旧画面当成当前屏幕。
     */
    val stale: Boolean = false,
    override val source: String = RecordSource.SCRIPT,
    override val seq: Long = 0L,
    override val elapsedMs: Long = 0L,
) : RecordEvent {
    override fun toJson(): JSONObject = RecordEvent.baseJson(this, RecordFormat.TYPE_SCREENSHOT).apply {
        put("frame", frame)
        put("width", width)
        put("height", height)
        if (stale) put("stale", true)
    }
}

data class ClickEvent(
    override val timestamp: Long,
    val x: Int,
    val y: Int,
    val success: Boolean,
    override val source: String = RecordSource.SCRIPT,
    override val seq: Long = 0L,
    override val elapsedMs: Long = 0L,
) : RecordEvent {
    override val ok: Boolean get() = success

    override fun toJson(): JSONObject = RecordEvent.baseJson(this, "click").apply {
        put("x", x)
        put("y", y)
    }
}

data class LongClickEvent(
    override val timestamp: Long,
    val x: Int,
    val y: Int,
    val durationMs: Long,
    val success: Boolean,
    override val source: String = RecordSource.SCRIPT,
    override val seq: Long = 0L,
    override val elapsedMs: Long = 0L,
) : RecordEvent {
    override val ok: Boolean get() = success

    override fun toJson(): JSONObject = RecordEvent.baseJson(this, "long_click").apply {
        put("x", x)
        put("y", y)
        put("durationMs", durationMs)
    }
}

data class SwipeEvent(
    override val timestamp: Long,
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val durationMs: Long,
    val success: Boolean,
    override val source: String = RecordSource.SCRIPT,
    override val seq: Long = 0L,
    override val elapsedMs: Long = 0L,
) : RecordEvent {
    override val ok: Boolean get() = success

    override fun toJson(): JSONObject = RecordEvent.baseJson(this, "swipe").apply {
        put("x1", x1)
        put("y1", y1)
        put("x2", x2)
        put("y2", y2)
        put("durationMs", durationMs)
    }
}

data class GestureEvent(
    override val timestamp: Long,
    val strokes: JSONArray,
    val success: Boolean,
    override val source: String = RecordSource.SCRIPT,
    override val seq: Long = 0L,
    override val elapsedMs: Long = 0L,
) : RecordEvent {
    override val ok: Boolean get() = success

    override fun toJson(): JSONObject = RecordEvent.baseJson(this, "gesture").apply {
        put("strokes", strokes)
    }
}

data class PressKeyEvent(
    override val timestamp: Long,
    val keyCode: Int,
    val success: Boolean,
    override val source: String = RecordSource.SCRIPT,
    override val seq: Long = 0L,
    override val elapsedMs: Long = 0L,
) : RecordEvent {
    override val ok: Boolean get() = success

    override fun toJson(): JSONObject = RecordEvent.baseJson(this, "press_key").apply {
        put("keyCode", keyCode)
    }
}

data class InputTextEvent(
    override val timestamp: Long,
    val text: String,
    val method: String,
    val success: Boolean,
    override val source: String = RecordSource.SCRIPT,
    override val seq: Long = 0L,
    override val elapsedMs: Long = 0L,
) : RecordEvent {
    override val ok: Boolean get() = success

    override fun toJson(): JSONObject = RecordEvent.baseJson(this, "input_text").apply {
        put("text", text)
        put("method", method)
    }
}

data class GlobalActionEvent(
    override val timestamp: Long,
    val action: Int,
    val success: Boolean,
    override val source: String = RecordSource.SCRIPT,
    override val seq: Long = 0L,
    override val elapsedMs: Long = 0L,
) : RecordEvent {
    override val ok: Boolean get() = success

    override fun toJson(): JSONObject = RecordEvent.baseJson(this, "global_action").apply {
        put("action", action)
    }
}

data class ShellEvent(
    override val timestamp: Long,
    val cmd: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val success: Boolean,
    override val source: String = RecordSource.SCRIPT,
    override val seq: Long = 0L,
    override val elapsedMs: Long = 0L,
) : RecordEvent {
    override val ok: Boolean get() = success

    override fun toJson(): JSONObject = RecordEvent.baseJson(this, "shell").apply {
        put("cmd", cmd)
        put("stdout", stdout)
        put("stderr", stderr)
        put("exitCode", exitCode)
    }
}

data class AppEvent(
    override val timestamp: Long,
    val action: String,
    val packageName: String,
    val success: Boolean,
    override val source: String = RecordSource.SCRIPT,
    override val seq: Long = 0L,
    override val elapsedMs: Long = 0L,
) : RecordEvent {
    override val ok: Boolean get() = success

    override fun toJson(): JSONObject = RecordEvent.baseJson(this, "app").apply {
        put("action", action)
        put("package", packageName)
    }
}

/**
 * 用户真实触摸动作（来自 /dev/input 或 getevent）。
 *
 * 同时保存原始像素坐标与 0~1 归一化坐标，并附带动作发生时的屏幕方向、前台包名、
 * 命中控件与最近截图，供 AI 分析「用户点了什么、为什么点」。
 */
data class UserActionEvent(
    override val timestamp: Long,
    val kind: UserActionKind,
    val x: Int = 0,
    val y: Int = 0,
    val x2: Int = 0,
    val y2: Int = 0,
    val nx: Double = 0.0,
    val ny: Double = 0.0,
    val nx2: Double = 0.0,
    val ny2: Double = 0.0,
    val durationMs: Long = 0L,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val rotation: Int = 0,
    val packageName: String = "",
    val targets: List<UiTarget> = emptyList(),
    val beforeFrame: String? = null,
    val afterFrame: String? = null,
    val inputSource: String = "",
    val strokes: JSONArray? = null,
    val pointerCount: Int = 1,
    override val source: String = RecordSource.USER,
    override val seq: Long = 0L,
    override val elapsedMs: Long = 0L,
) : RecordEvent {
    override fun toJson(): JSONObject = RecordEvent.baseJson(this, "user_action", source).apply {
        put("kind", kind.jsonValue)
        if (kind == UserActionKind.TAP || kind == UserActionKind.LONG_CLICK) {
            put("x", x)
            put("y", y)
            put("nx", nx)
            put("ny", ny)
        } else {
            put("x1", x)
            put("y1", y)
            put("x2", x2)
            put("y2", y2)
            put("nx1", nx)
            put("ny1", ny)
            put("nx2", nx2)
            put("ny2", ny2)
        }
        put("durationMs", durationMs)
        if (screenWidth > 0 && screenHeight > 0) {
            put(
                "screen",
                JSONObject()
                    .put("w", screenWidth)
                    .put("h", screenHeight)
                    .put("rotation", rotation),
            )
        }
        if (packageName.isNotBlank()) put("package", packageName)
        if (targets.isNotEmpty()) put("targets", UiTarget.listToJson(targets))
        beforeFrame?.let { put("beforeFrame", it) }
        afterFrame?.let { put("afterFrame", it) }
        if (inputSource.isNotBlank()) put("inputSource", inputSource)
        if (pointerCount > 1) put("pointerCount", pointerCount)
        strokes?.let { put("strokes", it) }
    }

    companion object {
        fun fromJson(
            json: JSONObject,
            atMs: Long = if (json.has("atMs")) json.optLong("atMs") else json.optLong("timestamp"),
            seq: Long = json.optLong("seq"),
            elapsedMs: Long = json.optLong("elapsedMs"),
            source: String = RecordSource.normalize(json.optString("source")),
            ok: Boolean = json.optBoolean("ok", true),
        ): UserActionEvent? {
            val kind = UserActionKind.from(json.optString("kind")) ?: return null
            val screen = json.optJSONObject("screen")
            val isTap = kind == UserActionKind.TAP || kind == UserActionKind.LONG_CLICK
            return UserActionEvent(
                timestamp = atMs,
                kind = kind,
                x = if (isTap) json.optInt("x") else json.optInt("x1"),
                y = if (isTap) json.optInt("y") else json.optInt("y1"),
                x2 = json.optInt("x2"),
                y2 = json.optInt("y2"),
                nx = if (isTap) json.optDouble("nx") else json.optDouble("nx1"),
                ny = if (isTap) json.optDouble("ny") else json.optDouble("ny1"),
                nx2 = json.optDouble("nx2"),
                ny2 = json.optDouble("ny2"),
                durationMs = json.optLong("durationMs"),
                screenWidth = screen?.optInt("w") ?: 0,
                screenHeight = screen?.optInt("h") ?: 0,
                rotation = screen?.optInt("rotation") ?: 0,
                packageName = json.optString("package"),
                targets = UiTarget.listFromJson(json.optJSONArray("targets")),
                beforeFrame = json.optString("beforeFrame").takeIf { it.isNotBlank() },
                afterFrame = json.optString("afterFrame").takeIf { it.isNotBlank() },
                inputSource = json.optString("inputSource"),
                strokes = json.optJSONArray("strokes"),
                pointerCount = json.optInt("pointerCount", 1).coerceAtLeast(1),
                source = source,
                seq = seq,
                elapsedMs = elapsedMs,
            )
        }
    }
}
