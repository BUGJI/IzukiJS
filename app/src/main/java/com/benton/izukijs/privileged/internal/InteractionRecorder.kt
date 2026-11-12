package com.benton.izukijs.privileged.internal

import android.content.Context
import android.os.SystemClock
import com.benton.izukijs.model.TouchInputDevice
import com.benton.izukijs.model.UnlockRecordingParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * 持续录制用户真实触摸（不锁屏、不唤醒）。
 *
 * 与 [GestureRecorder] 的区别：
 * - 不做 lock / wake / keyguard 等待，应用在任意界面都可录制；
 * - 轨迹闭合后立刻入队，由 App 侧定时 poll 拉取增量；
 * - 输出逻辑屏幕坐标、归一化坐标、屏幕方向与动作分类。
 */
internal class InteractionRecorder(private val context: Context?) {

    private val lock = Any()

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var reader: InputEventReader? = null

    @Volatile
    private var cancelSignal = CountDownLatch(1)

    @Volatile
    private var status: String = STATUS_IDLE

    @Volatile
    private var error: String? = null

    private val pending = ArrayList<JSONObject>()

    private var rawEvents = 0L
    private var strokeCount = 0L
    private var startedAtUptime: Long = SystemClock.uptimeMillis()
    private var generation = 0L

    fun start(timeoutMs: Int) {
        synchronized(lock) {
            if (worker?.isAlive == true && status == STATUS_RECORDING) {
                PrivilegedLog.w("InteractionRecorder: already recording")
                return
            }
            val myGeneration = ++generation
            cancelSignal.countDown()
            runCatching { reader?.close() }
            val signal = CountDownLatch(1)
            cancelSignal = signal
            status = STATUS_RECORDING
            error = null
            pending.clear()
            rawEvents = 0L
            strokeCount = 0L
            startedAtUptime = SystemClock.uptimeMillis()
            val budget = if (timeoutMs > 0) timeoutMs.toLong() else Long.MAX_VALUE
            val deadline = if (budget == Long.MAX_VALUE) Long.MAX_VALUE
            else SystemClock.uptimeMillis() + budget
            worker = Thread({
                runCatching { record(deadline, signal, myGeneration) }.getOrElse {
                    PrivilegedLog.e("InteractionRecorder: record failed", it)
                    synchronized(lock) {
                        if (generation == myGeneration) {
                            status = STATUS_FAILED
                            error = it.message ?: it.javaClass.simpleName
                        }
                    }
                }
            }, "izuki-interaction-record").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun poll(): String = synchronized(lock) {
        val events = JSONArray()
        pending.forEach { events.put(it) }
        pending.clear()
        val snapshot = JSONObject()
            .put("status", status)
            .put("events", events)
            .put("rawEvents", rawEvents)
            .put("strokes", strokeCount)
            .put("startUptime", startedAtUptime)
        error?.let { snapshot.put("error", it) }
        if (status == STATUS_STOPPED || status == STATUS_FAILED) {
            status = STATUS_IDLE
            error = null
        }
        snapshot.toString()
    }

    fun stop() {
        PrivilegedLog.i("InteractionRecorder: stop requested")
        cancelSignal.countDown()
        runCatching { reader?.close() }
        synchronized(lock) {
            if (status == STATUS_RECORDING) status = STATUS_STOPPED
        }
    }

    private fun record(deadline: Long, signal: CountDownLatch, myGeneration: Long) {
        val device = findTouchDevice()
        if (device == null) {
            synchronized(lock) {
                if (generation == myGeneration) {
                    status = STATUS_FAILED
                    error = "no readable touch device"
                }
            }
            return
        }
        val stream = InputEventReader.open(device.path)
        if (stream == null) {
            synchronized(lock) {
                if (generation == myGeneration) {
                    status = STATUS_FAILED
                    error = "cannot open ${device.path}"
                }
            }
            return
        }
        if (generation != myGeneration) {
            runCatching { stream.close() }
            return
        }
        reader = stream

        var screen = HiddenDisplayManager.current() ?: fallbackScreen()
        var lastScreenRefresh = SystemClock.uptimeMillis()

        fun currentScreen(): ScreenGeometry? {
            val now = SystemClock.uptimeMillis()
            if (screen == null || now - lastScreenRefresh > SCREEN_REFRESH_MS) {
                screen = HiddenDisplayManager.current() ?: screen ?: fallbackScreen()
                lastScreenRefresh = now
            }
            return screen
        }

        // Protocol B 需要 TRACKING_ID 才能可靠区分多指；只有 SLOT 的设备回退单指解析。
        val multiParser = if (device.hasTrackingId) {
            MultiTouchStreamParser().apply {
                onGestureClosed = gesture@{ gesture ->
                    if (generation != myGeneration) return@gesture
                    val geometry = currentScreen() ?: return@gesture
                    enqueue(gesture, device, geometry)
                }
            }
        } else {
            null
        }
        val singleParser = if (multiParser == null) {
            TouchStreamParser().apply {
                streaming = true
                onStrokeClosed = stroke@{ stroke ->
                    if (generation != myGeneration) return@stroke
                    val geometry = currentScreen() ?: return@stroke
                    enqueue(TouchGesture(listOf(stroke), 1), device, geometry)
                }
            }
        } else {
            null
        }

        try {
            stream.readLoop { type, code, value, atMs ->
                synchronized(lock) { rawEvents++ }
                if (signal.count == 0L) return@readLoop
                if (deadline != Long.MAX_VALUE && SystemClock.uptimeMillis() >= deadline) {
                    runCatching { stream.close() }
                    return@readLoop
                }
                val tMs = relativeTime(atMs)
                if (multiParser != null) {
                    synchronized(multiParser) { multiParser.onEvent(type, code, value, tMs) }
                } else {
                    synchronized(singleParser!!) { singleParser.onEvent(type, code, value, tMs) }
                }
            }
        } finally {
            runCatching { stream.close() }
            val endMs = relativeTime(SystemClock.uptimeMillis())
            multiParser?.let { synchronized(it) { it.finish(endMs) } }
            singleParser?.let { synchronized(it) { it.finish(endMs) } }
            synchronized(lock) {
                if (generation == myGeneration && status == STATUS_RECORDING) {
                    status = STATUS_STOPPED
                }
            }
        }
    }

    private fun relativeTime(atMs: Long): Int {
        val started = startedAtUptime
        return ((atMs - started).coerceIn(0L, Int.MAX_VALUE.toLong())).toInt()
    }

    private fun enqueue(gesture: TouchGesture, device: TouchInputDevice, screen: ScreenGeometry) {
        val mapped = TouchCoordMapper.mapStrokes(gesture.strokes, device, screen)
            .filter { it.points.isNotEmpty() }
        if (mapped.isEmpty()) return
        val firstStroke = mapped.first()
        val first = firstStroke.points.first()
        val last = firstStroke.points.last()
        val startMs = mapped.minOf { it.points.first().tMs }
        val endMs = mapped.maxOf { it.endTMs }
        val duration = (endMs - startMs).coerceAtLeast(1)
        val dx = last.x - first.x
        val dy = last.y - first.y
        val distance = kotlin.math.hypot(dx.toDouble(), dy.toDouble())
        val path = pathLength(firstStroke)
        val pointerCount = gesture.pointerCount.coerceAtLeast(mapped.size).coerceAtLeast(1)
        val kind = when {
            pointerCount > 1 && mapped.all { isLinear(it) } -> "multi_swipe"
            pointerCount > 1 -> "gesture"
            distance <= TAP_SLOP && duration < LONG_PRESS_MS -> "tap"
            distance <= TAP_SLOP -> "long_click"
            isComplexPath(firstStroke, distance, path) -> "gesture"
            else -> "swipe"
        }
        val json = JSONObject()
            .put("kind", kind)
            .put("x", first.x)
            .put("y", first.y)
            .put("nx", first.x.toDouble() / screen.width.coerceAtLeast(1))
            .put("ny", first.y.toDouble() / screen.height.coerceAtLeast(1))
            .put("durationMs", duration)
            .put("pathLength", path)
            .put("pointerCount", pointerCount)
            .put(
                "screen",
                JSONObject()
                    .put("w", screen.width)
                    .put("h", screen.height)
                    .put("rotation", screen.rotation),
            )
        if (kind != "tap" && kind != "long_click") {
            json.put("x2", last.x)
                .put("y2", last.y)
                .put("nx2", last.x.toDouble() / screen.width.coerceAtLeast(1))
                .put("ny2", last.y.toDouble() / screen.height.coerceAtLeast(1))
        }
        val pointArray = JSONArray()
        firstStroke.points.forEach { point ->
            pointArray.put(JSONArray(listOf(point.x, point.y, point.tMs)))
        }
        json.put("points", pointArray)
        if (mapped.size > 1 || kind == "gesture") {
            json.put(
                "strokes",
                JSONArray().apply {
                    mapped.forEach { stroke ->
                        put(
                            JSONArray().apply {
                                stroke.points.forEach { point ->
                                    put(JSONArray(listOf(point.x, point.y, point.tMs)))
                                }
                            },
                        )
                    }
                },
            )
        }
        synchronized(lock) {
            if (pending.size >= MAX_PENDING) pending.removeAt(0)
            pending.add(json)
            strokeCount++
        }
    }

    private fun pathLength(stroke: TouchStroke): Double =
        stroke.points.zipWithNext().sumOf { (a, b) ->
            kotlin.math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
        }

    private fun isLinear(stroke: TouchStroke): Boolean {
        val points = stroke.points
        if (points.size < 2) return true
        val distance = kotlin.math.hypot(
            (points.last().x - points.first().x).toDouble(),
            (points.last().y - points.first().y).toDouble(),
        )
        return pathLength(stroke) <= distance * LINEAR_SLACK + LINEAR_MIN_SLACK
    }

    private fun isComplexPath(stroke: TouchStroke, distance: Double, path: Double): Boolean {
        if (path > distance * COMPLEX_RATIO + COMPLEX_MIN_SLACK) return true
        var turns = 0
        var previousDx = 0
        var previousDy = 0
        val points = stroke.points
        for (index in 1 until points.size) {
            val dx = points[index].x - points[index - 1].x
            val dy = points[index].y - points[index - 1].y
            if (kotlin.math.abs(dx) + kotlin.math.abs(dy) < TURN_SLOP) continue
            if (previousDx != 0 || previousDy != 0) {
                val dot = dx.toLong() * previousDx + dy.toLong() * previousDy
                val mag = kotlin.math.hypot(dx.toDouble(), dy.toDouble()) *
                    kotlin.math.hypot(previousDx.toDouble(), previousDy.toDouble())
                if (mag > 0 && dot / mag < TURN_COS) turns++
            }
            previousDx = dx
            previousDy = dy
        }
        return turns >= COMPLEX_TURNS
    }

    private fun findTouchDevice(): TouchInputDevice? {
        val output = runGetevent()
        if (output.isNullOrBlank()) {
            PrivilegedLog.w("InteractionRecorder: getevent -lp produced nothing")
            return null
        }
        val candidates = UnlockRecordingParser.parseTouchDevices(output)
        val readable = candidates.firstOrNull { File(it.path).canRead() }
        if (readable == null) {
            PrivilegedLog.w(
                "InteractionRecorder: no readable candidates: " +
                    candidates.joinToString { it.path },
            )
        }
        return readable
    }

    private fun fallbackScreen(): ScreenGeometry? = runCatching {
        val metrics = context?.resources?.displayMetrics ?: return null
        ScreenGeometry(metrics.widthPixels, metrics.heightPixels, 0)
    }.getOrNull()

    private fun runGetevent(): String? = runCatching {
        val process = ProcessBuilder("/system/bin/getevent", "-lp")
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text
    }.onFailure {
        PrivilegedLog.w("InteractionRecorder: getevent failed: ${it.message}")
    }.getOrNull()

    companion object {
        private const val STATUS_IDLE = "IDLE"
        private const val STATUS_RECORDING = "RECORDING"
        private const val STATUS_STOPPED = "STOPPED"
        private const val STATUS_FAILED = "FAILED"

        private const val TAP_SLOP = 24.0
        private const val LONG_PRESS_MS = 500
        private const val SCREEN_REFRESH_MS = 1_000L
        private const val MAX_PENDING = 2_000
        private const val LINEAR_SLACK = 1.35
        private const val LINEAR_MIN_SLACK = 8.0
        private const val COMPLEX_RATIO = 1.8
        private const val COMPLEX_MIN_SLACK = 24.0
        private const val TURN_SLOP = 4
        private const val TURN_COS = 0.5
        private const val COMPLEX_TURNS = 3
    }
}
