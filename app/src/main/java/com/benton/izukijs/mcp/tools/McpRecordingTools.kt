package com.benton.izukijs.mcp.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.benton.izukijs.controller.record.AppEvent
import com.benton.izukijs.controller.record.ClickEvent
import com.benton.izukijs.controller.record.annotationScale
import com.benton.izukijs.controller.record.ContextEvent
import com.benton.izukijs.controller.record.GestureEvent
import com.benton.izukijs.controller.record.GlobalActionEvent
import com.benton.izukijs.controller.record.InputTextEvent
import com.benton.izukijs.controller.record.LongClickEvent
import com.benton.izukijs.controller.record.PressKeyEvent
import com.benton.izukijs.controller.record.RecordEvent
import com.benton.izukijs.controller.record.RecordingReader
import com.benton.izukijs.controller.record.RecordingScriptExporter
import com.benton.izukijs.controller.record.RecordingSession
import com.benton.izukijs.controller.record.RecordingValidator
import com.benton.izukijs.controller.record.ScreenshotEvent
import com.benton.izukijs.controller.record.SessionStartEvent
import com.benton.izukijs.controller.record.ShellEvent
import com.benton.izukijs.controller.record.SwipeEvent
import com.benton.izukijs.controller.record.UserActionEvent
import com.benton.izukijs.controller.record.UserActionKind
import com.benton.izukijs.controller.record.UserRecordingOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

internal fun McpToolRegistry.recordingTool(session: RecordingSession): McpTool = McpTool(
    name = "recording",
    description = "读取用户操作录制（真实点击 / 滑动 / 截图 / 命中控件），供 AI 分析并辅助编写脚本。" +
        "`action=list` 列出录制；`info` 查看详情；`timeline` 返回紧凑动作时间线（推荐先调用）；" +
        "`events` 返回完整事件；`frame` 返回指定帧图像块（可用 annotate 标出点击位置）；" +
        "`validate` 校验录制结构；`export_script` 生成 JS 脚本骨架。" +
        "start / stop / delete 需在 MCP 设置中开启「允许录制控制」。",
    inputSchema = McpSchemas.objectSchema(
        mapOf(
            "action" to McpSchemas.enum(
                "操作类型",
                listOf(
                    "list", "info", "timeline", "events", "frame", "validate",
                    "start", "stop", "delete", "export_script",
                ),
            ),
            "id" to McpSchemas.string("录制 id（除 list / start 外必填）"),
            "frame" to McpSchemas.string("帧相对路径，例如 frames/frame_00012.jpg"),
            "timestamp" to McpSchemas.integer("按录制内时间（毫秒）就近取帧"),
            "offset" to McpSchemas.integer("分页偏移，默认 0"),
            "limit" to McpSchemas.integer("返回条数，默认 200，最大 1000"),
            "types" to McpSchemas.stringArray(
                "只看这些事件类型：tap / long_click / swipe / gesture / click / long_click / " +
                    "swipe / text / app / key / shell / screenshot",
            ),
            "since_ms" to McpSchemas.integer("只看时间戳 >= 该值的事件"),
            "annotate" to McpSchemas.boolean("在返回的帧上标出最近动作的坐标，默认 true"),
            "scale" to McpSchemas.number("帧缩放比例 0.1~1，默认 1"),
            "quality" to McpSchemas.integer("JPEG 质量 1~100，默认 90"),
            "region" to McpSchemas.intArray("裁剪区域 [left,top,right,bottom]（帧像素）"),
            "name" to McpSchemas.string("start 时的录制名称，默认 mcp-user-recording"),
            "capture_screenshots" to McpSchemas.boolean("start 时是否录制截图，默认 true"),
            "capture_context" to McpSchemas.boolean("start 时是否记录控件信息，默认 true"),
            "sample_interval_ms" to McpSchemas.integer("start 时截图间隔，默认 700"),
        ),
        required = listOf("action"),
    ),
) { args ->
    val action = args.optString("action").trim().lowercase()
    when (action) {
        "list" -> listRecordings(session)
        "info" -> infoRecording(session, args)
        "timeline" -> timelineRecording(session, args)
        "events" -> eventsRecording(session, args)
        "frame" -> frameRecording(session, args)
        "validate" -> validateRecording(session, args)
        "start" -> startRecording(session, args)
        "stop" -> stopRecording(session)
        "delete" -> deleteRecording(session, args)
        "export_script" -> exportRecordingScript(session, args)
        else -> McpToolResult.error("未知 action: $action")
    }
}

private fun listRecordings(session: RecordingSession): McpToolResult {
    val array = JSONArray()
    session.store.list().forEach { array.put(it.toJson()) }
    return McpToolResult.text(
        JSONObject().put("ok", true).put("recordings", array).toString(),
    )
}

private fun infoRecording(session: RecordingSession, args: JSONObject): McpToolResult {
    val id = args.optString("id").ifBlank { return McpToolResult.error("缺少 id") }
    val meta = session.store.info(id) ?: return McpToolResult.error("录制不存在: $id")
    // 流式汇总，避免 info 为了统计把几百 MB 的事件全部读进内存。
    val summary = session.store.summary(id) ?: return McpToolResult.error("录制不存在: $id")
    val payload = meta.toJson()
        .put("ok", true)
        .put("eventCount", summary.eventCount)
        .put("durationMs", summary.durationMs)
        .put("userActions", summary.userActions)
        .put("scriptActions", summary.scriptActions)
        .put("frames", summary.frames)
        .put("packages", JSONArray(summary.packages))
    return McpToolResult.text(payload.toString())
}

private fun timelineRecording(session: RecordingSession, args: JSONObject): McpToolResult {
    val reader = loadReader(session, args) ?: return McpToolResult.error("录制不存在或缺少 id")
    val types = args.optJSONArray("types")?.let { array ->
        buildSet {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }
    val since = args.optLong("since_ms", 0L)
    val offset = args.optInt("offset", 0).coerceAtLeast(0)
    val limit = args.optInt("limit", 200).coerceIn(1, 1000)
    val rows = ArrayList<JSONObject>()
    reader.events.forEach { event ->
        if (event.timestamp < since) return@forEach
        val row = timelineRow(event) ?: return@forEach
        val type = row.optString("type")
        if (types != null && type !in types) return@forEach
        rows.add(row)
    }
    val page = rows.drop(offset).take(limit)
    val array = JSONArray()
    page.forEach { array.put(it) }
    return McpToolResult.text(
        JSONObject()
            .put("ok", true)
            .put("id", reader.meta.id)
            .put("name", reader.meta.name)
            .put("total", rows.size)
            .put("offset", offset)
            .put("items", array)
            .toString(),
    )
}

private fun eventsRecording(session: RecordingSession, args: JSONObject): McpToolResult {
    val reader = loadReader(session, args) ?: return McpToolResult.error("录制不存在或缺少 id")
    val offset = args.optInt("offset", 0).coerceAtLeast(0)
    val limit = args.optInt("limit", 200).coerceIn(1, 1000)
    val array = JSONArray()
    reader.events.drop(offset).take(limit).forEach { array.put(it.toJson()) }
    return McpToolResult.text(
        JSONObject()
            .put("ok", true)
            .put("id", reader.meta.id)
            .put("total", reader.events.size)
            .put("offset", offset)
            .put("items", array)
            .toString(),
    )
}

private fun frameRecording(session: RecordingSession, args: JSONObject): McpToolResult {
    val reader = loadReader(session, args) ?: return McpToolResult.error("录制不存在或缺少 id")
    val framePath = args.optString("frame").trim()
    val timestamp = args.optLong("timestamp", -1L)
    val screenshot = when {
        framePath.isNotBlank() -> reader.events
            .filterIsInstance<ScreenshotEvent>()
            .firstOrNull { it.frame == framePath || it.frame.endsWith(framePath) }
        timestamp >= 0L -> reader.events
            .filterIsInstance<ScreenshotEvent>()
            .minByOrNull { kotlin.math.abs(it.timestamp - timestamp) }
        else -> reader.events.filterIsInstance<ScreenshotEvent>().firstOrNull()
    } ?: return McpToolResult.error("找不到截图帧；该录制可能未开启截图")

    val decoded = reader.frame(screenshot) ?: return McpToolResult.error("帧解码失败: ${screenshot.frame}")
    val annotate = args.optBoolean("annotate", true)
    val action = if (annotate) nearestAction(reader, screenshot.timestamp) else null
    val annotated = if (action != null) annotateFrame(decoded, action) else decoded
    try {
        val encoded = encodeRecordingFrame(annotated, args)
        val meta = JSONObject()
            .put("ok", true)
            .put("id", reader.meta.id)
            .put("frame", screenshot.frame)
            .put("timestamp", screenshot.timestamp)
            .put("image_size", JSONObject().put("w", encoded.width).put("h", encoded.height))
            .put("screen_size", JSONObject().put("w", encoded.screenWidth).put("h", encoded.screenHeight))
        action?.let { meta.put("action", timelineRow(it)) }
        return McpToolResult(
            listOf(
                McpContent.image(encoded.base64, encoded.mimeType),
                McpContent.text(meta.toString()),
            ),
        )
    } finally {
        if (annotated !== decoded) annotated.recycle()
        decoded.recycle()
        // reader 缓存最多持有 8 张全屏 Bitmap；MCP 每次调用都应尽快释放。
        reader.clearFrameCache()
    }
}

private fun validateRecording(session: RecordingSession, args: JSONObject): McpToolResult {
    val id = args.optString("id").ifBlank { return McpToolResult.error("缺少 id") }
    val reader = session.store.load(id) ?: return McpToolResult.error("录制不存在: $id")
    val validation = RecordingValidator.validate(reader)
    return McpToolResult.text(
        JSONObject()
            .put("ok", true)
            .put("id", id)
            .put("validation", validation.toJson())
            .toString(),
    )
}

private fun McpToolRegistry.startRecording(session: RecordingSession, args: JSONObject): McpToolResult {
    if (!allowRecordingControl()) return McpToolResult.error(RECORDING_DISABLED)
    val options = UserRecordingOptions(
        name = args.optString("name").ifBlank { "mcp-user-recording" },
        captureScreenshots = args.optBoolean("capture_screenshots", true),
        sampleIntervalMs = args.optInt("sample_interval_ms", 700).toLong().coerceIn(250L, 5000L),
        captureContext = args.optBoolean("capture_context", true),
    )
    if (!session.canCaptureUserInput()) {
        return McpToolResult.error(
            "没有可用的触摸采集后端：请启用 Shizuku / Root，或连接无线调试",
        )
    }
    if (session.isRecording() && !session.isUserRecording()) {
        return McpToolResult.error("已有脚本录制进行中，无法启动用户录制")
    }
    val starter = userRecordingStarter ?: return startRecordingDirect(session, options)
    if (!starter(options)) {
        return McpToolResult.error("启动录制失败：无法启动录制前台服务")
    }
    val started = runCatching {
        runBlocking { withTimeoutOrNull(START_TIMEOUT_MS) { session.active.first { it } } }
    }.getOrNull() == true
    val id = session.currentRecordingId()
    if (!started || id == null) {
        return McpToolResult.error("启动录制失败：前台服务未在 ${START_TIMEOUT_MS}ms 内就绪")
    }
    return McpToolResult.text(JSONObject().put("ok", true).put("id", id).toString())
}

private fun startRecordingDirect(
    session: RecordingSession,
    options: UserRecordingOptions,
): McpToolResult {
    val id = session.startUserRecording(options)
        ?: return McpToolResult.error("启动录制失败：当前没有可用的控制后端")
    return McpToolResult.text(JSONObject().put("ok", true).put("id", id).toString())
}

private const val START_TIMEOUT_MS = 3_000L

private fun McpToolRegistry.stopRecording(session: RecordingSession): McpToolResult {
    if (!allowRecordingControl()) return McpToolResult.error(RECORDING_DISABLED)
    if (!session.isUserRecording()) return McpToolResult.error("当前没有用户录制")
    val id = session.stop() ?: return McpToolResult.error("当前没有进行中的录制")
    // 停止会话后同步停掉 MCP 启动的前台服务，避免常驻通知残留。
    userRecordingStopper?.invoke()
    return McpToolResult.text(JSONObject().put("ok", true).put("id", id).toString())
}

private fun McpToolRegistry.deleteRecording(session: RecordingSession, args: JSONObject): McpToolResult {
    if (!allowRecordingControl()) return McpToolResult.error(RECORDING_DISABLED)
    val id = args.optString("id").ifBlank { return McpToolResult.error("缺少 id") }
    if (id == session.currentRecordingId()) {
        return McpToolResult.error("录制进行中，无法删除")
    }
    val ok = session.store.delete(id)
    return McpToolResult.text(JSONObject().put("ok", ok).put("id", id).toString())
}

private fun exportRecordingScript(session: RecordingSession, args: JSONObject): McpToolResult {
    val reader = loadReader(session, args) ?: return McpToolResult.error("录制不存在或缺少 id")
    val script = RecordingScriptExporter.build(reader)
    return McpToolResult.text(
        JSONObject().put("ok", true).put("id", reader.meta.id).put("script", script).toString(),
    )
}

private fun McpToolRegistry.allowRecordingControl(): Boolean =
    configProvider().allowRecording

private fun loadReader(session: RecordingSession, args: JSONObject): RecordingReader? {
    val id = args.optString("id").ifBlank { return null }
    return session.store.load(id)
}

private fun timelineRow(event: RecordEvent): JSONObject? = when (event) {
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
        event.beforeFrame?.let { put("frame", it) }
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
        .put("type", "screencap").put("frame", event.frame)
        .put("w", event.width).put("h", event.height)
        .apply { if (event.stale) put("stale", true) }
    // context 已合并回动作；session_start 不是时间线动作。
    is SessionStartEvent, is ContextEvent -> null
}

private fun nearestAction(reader: RecordingReader, timestamp: Long): RecordEvent? =
    reader.events.asSequence()
        .filter { it is UserActionEvent || it is ClickEvent || it is LongClickEvent || it is SwipeEvent }
        .minByOrNull { kotlin.math.abs(it.timestamp - timestamp) }
        ?.takeIf { kotlin.math.abs(it.timestamp - timestamp) <= 1_500L }

private fun annotateFrame(source: Bitmap, action: RecordEvent): Bitmap {
    val copy = runCatching { source.copy(Bitmap.Config.ARGB_8888, true) }.getOrNull() ?: return source
    val canvas = Canvas(copy)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = (copy.width / 240f).coerceAtLeast(3f)
    }
    val actionScreenWidth = (action as? UserActionEvent)?.screenWidth ?: 0
    val actionScreenHeight = (action as? UserActionEvent)?.screenHeight ?: 0
    // 动作与帧方向不一致 / 分辨率缺失时，坐标属于另一套画布，继续标注只会漂出画面。
    val scale = annotationScale(
        frameWidth = copy.width,
        frameHeight = copy.height,
        screenWidth = actionScreenWidth,
        screenHeight = actionScreenHeight,
    ) ?: return copy
    val scaleX = scale.x
    val scaleY = scale.y
    val radius = (copy.width / 22f).coerceAtLeast(24f)
    fun marker(x: Int, y: Int) {
        val cx = x * scaleX
        val cy = y * scaleY
        canvas.drawCircle(cx, cy, radius, paint)
        canvas.drawLine(cx - radius * 1.6f, cy, cx + radius * 1.6f, cy, paint)
        canvas.drawLine(cx, cy - radius * 1.6f, cx, cy + radius * 1.6f, paint)
    }
    when (action) {
        is UserActionEvent -> {
            val strokes = action.strokes
            if (strokes != null && strokes.length() > 0) {
                for (index in 0 until strokes.length()) {
                    val stroke = strokes.optJSONArray(index) ?: continue
                    var previous: Pair<Int, Int>? = null
                    for (pointIndex in 0 until stroke.length()) {
                        val point = stroke.optJSONArray(pointIndex) ?: continue
                        val px = point.optInt(0)
                        val py = point.optInt(1)
                        val last = previous
                        if (last != null) {
                            canvas.drawLine(last.first * scaleX, last.second * scaleY, px * scaleX, py * scaleY, paint)
                        }
                        previous = px to py
                    }
                    stroke.optJSONArray(0)?.let { marker(it.optInt(0), it.optInt(1)) }
                    stroke.optJSONArray(stroke.length() - 1)?.let { marker(it.optInt(0), it.optInt(1)) }
                }
            } else if (action.kind == UserActionKind.SWIPE ||
                action.kind == UserActionKind.GESTURE ||
                action.kind == UserActionKind.MULTI_SWIPE
            ) {
                val startX = action.x * scaleX
                val startY = action.y * scaleY
                val endX = action.x2 * scaleX
                val endY = action.y2 * scaleY
                canvas.drawLine(startX, startY, endX, endY, paint)
                marker(action.x, action.y)
                marker(action.x2, action.y2)
            } else {
                marker(action.x, action.y)
            }
        }

        is SwipeEvent -> {
            canvas.drawLine(
                action.x1 * scaleX,
                action.y1 * scaleY,
                action.x2 * scaleX,
                action.y2 * scaleY,
                paint,
            )
            marker(action.x1, action.y1)
            marker(action.x2, action.y2)
        }

        is ClickEvent -> marker(action.x, action.y)
        is LongClickEvent -> marker(action.x, action.y)
        else -> Unit
    }
    return copy
}

private fun encodeRecordingFrame(bitmap: Bitmap, args: JSONObject): EncodedImage {
    val scale = args.optDouble("scale", 1.0).coerceIn(0.1, 1.0)
    val quality = args.optInt("quality", 90).coerceIn(1, 100)
    val region = args.optJSONArray("region")?.let { array ->
        if (array.length() < 4) null else Rect(
            array.optInt(0),
            array.optInt(1),
            array.optInt(2),
            array.optInt(3),
        )
    }
    return ScreenshotEncoder.encode(
        source = bitmap,
        scalePercent = (scale * 100).toInt(),
        jpegQuality = quality,
        region = region,
        format = ScreenshotFormat.JPEG,
    )
}

private const val RECORDING_DISABLED =
    "录制控制未开启：请在「设置 → MCP 服务 → 工具开关」中开启「允许录制控制」"
