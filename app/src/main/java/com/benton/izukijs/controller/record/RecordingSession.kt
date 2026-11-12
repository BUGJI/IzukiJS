package com.benton.izukijs.controller.record

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.DeviceController
import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.TouchInputDevice
import com.benton.izukijs.model.UnlockRecordingParser
import com.benton.izukijs.model.UnlockStroke
import com.benton.izukijs.privileged.PrivilegedUnlockClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import kotlin.math.hypot

/**
 * 把特权进程的相对时间换算成应用侧墙钟时间。
 *
 * 特权进程与应用进程都用 uptimeMillis，但升级 / 重连后可能拿到异常值；
 * 只有落在本次录制时间窗内才用跨进程时间换算，否则退回会话起点 + 相对时间。
 */
internal fun resolveEventWall(
    nowWall: Long,
    nowUptime: Long,
    sessionStartWall: Long,
    sessionStartUptime: Long,
    batchStartUptime: Long,
    tMs: Long,
    toleranceMs: Long = 5_000L,
): Long {
    val eventUptime = batchStartUptime + tMs
    val plausible = batchStartUptime > 0L &&
        eventUptime >= sessionStartUptime - toleranceMs &&
        eventUptime <= nowUptime + toleranceMs
    return if (plausible) {
        nowWall - (nowUptime - eventUptime)
    } else {
        sessionStartWall + tMs
    }
}

/** 用户录制选项。 */
data class UserRecordingOptions(
    val name: String = "user-recording",
    val captureScreenshots: Boolean = true,
    val sampleIntervalMs: Long = 700L,
    val captureContext: Boolean = true,
)

class RecordingSession(
    private val context: Context,
    private val controllers: ControllerManager,
    private val logBus: LogBus,
    private val hooks: RecordingHooks = RecordingHooks(),
    private val privileged: PrivilegedUnlockClient? = null,
    private val captureProvider: (() -> Bitmap?)? = null,
    private val nodeTreeProvider: ((Long) -> NodeTreeResult)? = null,
    private val foregroundProvider: (() -> String?)? = null,
) {

    val store = RecordingStore(context)

    private val lock = Any()
    private var writer: RecordingWriter? = null
    private var originals: List<DeviceController> = emptyList()
    private var currentId: String? = null
    private var captureLoop: RecordingCaptureLoop? = null
    private var userScope: CoroutineScope? = null
    private var userJob: Job? = null
    private var contextExecutor: ExecutorService? = null
    private var contextResolver: RecordingContextResolver? = null
    private var stopping = false

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _activeKind = MutableStateFlow(RecordingMeta.KIND_SCRIPT)
    val activeKind: StateFlow<String> = _activeKind.asStateFlow()

    fun isRecording(): Boolean = _active.value

    fun currentRecordingId(): String? = synchronized(lock) { currentId }

    fun isUserRecording(): Boolean = _activeKind.value == RecordingMeta.KIND_USER && _active.value

    /** 是否有可用的真实触摸采集后端（Shizuku 特权或 Shell 级 getevent）。 */
    fun canCaptureUserInput(): Boolean =
        privileged?.isShizukuAvailable() == true ||
            controllers.controllerFor(Capability.SHELL) != null

    /** 脚本录制：记录脚本 / MCP 发出的控制动作。 */
    fun start(name: String): String? {
        synchronized(lock) {
            // 已有任何录制（含用户录制 / 正在收尾）时拒绝，避免脚本误以为启动成功。
            if (writer != null || stopping) {
                logBus.warn("录制失败：已有录制进行中")
                return null
            }
            val all = controllers.all()
            if (all.isEmpty()) {
                logBus.warn("录制失败：当前没有可用的控制后端")
                return null
            }
            val newWriter = store.create(
                name = name,
                kind = RecordingMeta.KIND_SCRIPT,
                device = deviceInfo(),
                options = JSONObject()
                    .put("captureScreenshots", true)
                    .put("sampleIntervalMs", 0L)
                    .put("captureContext", false),
            )
            newWriter.writeSessionStart(
                appVersion = appVersion(),
                device = deviceInfo(),
                options = JSONObject()
                    .put("captureScreenshots", true)
                    .put("sampleIntervalMs", 0L)
                    .put("captureContext", false),
            )
            originals = all
            wrapControllers(all, newWriter)
            writer = newWriter
            currentId = newWriter.id
            hooks.captureObserver = { bitmap, wallMs, elapsedMs ->
                newWriter.recordScreencapAsync(
                    bitmap = bitmap,
                    atMs = newWriter.timestampOf(wallMs),
                    elapsedMs = elapsedMs,
                    source = RecordSource.SCRIPT,
                )
            }
            hooks.appObserver = { action, packageName, success ->
                newWriter.record(
                    AppEvent(newWriter.timestamp(), action, packageName, success, RecordSource.SCRIPT),
                )
            }
            _activeKind.value = RecordingMeta.KIND_SCRIPT
            _active.value = true
            logBus.success("开始录制控制操作: ${newWriter.id}")
            return newWriter.id
        }
    }

    /** 用户录制：记录真实触摸 + 截图 + 命中控件，同时标注脚本 / MCP 动作来源。 */
    fun startUserRecording(options: UserRecordingOptions): String? {
        synchronized(lock) {
            if (writer != null || stopping) {
                return if (_activeKind.value == RecordingMeta.KIND_USER && !stopping) currentId else null
            }
            val optionsJson = JSONObject()
                .put("captureScreenshots", options.captureScreenshots)
                .put("sampleIntervalMs", options.sampleIntervalMs)
                .put("captureContext", options.captureContext)
            val newWriter = store.create(
                name = options.name,
                kind = RecordingMeta.KIND_USER,
                inputSource = "",
                framesAvailable = false,
                sampleIntervalMs = options.sampleIntervalMs,
                device = deviceInfo(),
                options = optionsJson,
            )
            newWriter.writeSessionStart(
                appVersion = appVersion(),
                device = deviceInfo(),
                options = optionsJson,
            )
            originals = controllers.all()
            wrapControllers(originals, newWriter)
            writer = newWriter
            currentId = newWriter.id
            hooks.appObserver = { action, packageName, success ->
                newWriter.record(
                    AppEvent(newWriter.timestamp(), action, packageName, success, RecordSource.SCRIPT),
                )
            }
            // 用户录制不走 captureObserver：采样线程自己决定何时截图，
            // 避免与脚本的 screencap 调用重复记录。
            hooks.captureObserver = null
            _activeKind.value = RecordingMeta.KIND_USER
            _active.value = true

            val provider = captureProvider
            val loop = if (options.captureScreenshots && provider != null) {
                RecordingCaptureLoop(
                    writer = newWriter,
                    intervalMs = options.sampleIntervalMs,
                    logBus = logBus,
                    captureProvider = provider,
                ).also { it.start() }
            } else {
                null
            }
            captureLoop = loop

            val resolver = RecordingContextResolver(
                nodeTreeProvider = nodeTreeProvider ?: { NodeTreeResult.NoBackend },
                foregroundProvider = foregroundProvider ?: { null },
                logBus = logBus,
            )
            contextResolver = resolver
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "izuki-recording-context").apply { isDaemon = true }
            }
            contextExecutor = executor

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            userScope = scope
            userJob = scope.launch { runUserInputLoop(newWriter, options, loop, resolver) }

            logBus.success("开始录制用户操作: ${newWriter.id}")
            return newWriter.id
        }
    }

    fun stop(): String? {
        val current: RecordingWriter
        val id: String?
        val wasUser: Boolean
        synchronized(lock) {
            if (stopping) return currentId
            current = writer ?: return null
            id = currentId
            wasUser = _activeKind.value == RecordingMeta.KIND_USER
            stopping = true
            hooks.captureObserver = null
            hooks.appObserver = null
            captureLoop?.stop()
            captureLoop = null
            userJob?.cancel()
            userJob = null
            userScope = null
            // 只恢复仍由录制包装器占位的后端；录制期间重连 / 替换的新后端不要被旧对象覆盖。
            val currentByMode = controllers.all().associateBy { it.mode }
            originals.forEach { original ->
                if (currentByMode[original.mode] is RecordingDeviceController) {
                    controllers.register(original)
                }
            }
            originals = emptyList()
        }
        // close() 会等待写入队列收尾，放到锁外避免阻塞其它录制状态查询。
        if (wasUser) {
            runCatching { privileged?.stopInteractionRecord() }
        }
        current.close()
        contextExecutor?.shutdownNow()
        contextExecutor = null
        contextResolver = null
        synchronized(lock) {
            writer = null
            currentId = null
            _active.value = false
            _activeKind.value = RecordingMeta.KIND_SCRIPT
            stopping = false
        }
        logBus.success("录制结束: $id")
        return id
    }

    private fun wrapControllers(all: List<DeviceController>, newWriter: RecordingWriter) {
        all.map { inner ->
            RecordingDeviceController(inner, newWriter, RecordSource.SCRIPT)
        }.forEach { controllers.register(it) }
    }

    private suspend fun runUserInputLoop(
        newWriter: RecordingWriter,
        options: UserRecordingOptions,
        loop: RecordingCaptureLoop?,
        resolver: RecordingContextResolver,
    ) {
        val sessionStartWall = System.currentTimeMillis()
        val sessionStartUptime = SystemClock.uptimeMillis()

        val privilegedClient = privileged
        try {
            if (privilegedClient != null && privilegedClient.startInteractionRecord(0)) {
                newWriter.setInputSource("shizuku")
                var consecutiveFailures = 0
                while (userScope?.isActive == true) {
                    delay(POLL_INTERVAL_MS)
                    val batch = runCatching { privilegedClient.pollInteractionRecord() }.getOrNull()
                    if (batch == null) {
                        consecutiveFailures++
                        if (consecutiveFailures >= MAX_POLL_FAILURES) {
                            logBus.warn("用户触摸录制：特权服务连续无响应，回退 Shell")
                            break
                        }
                        continue
                    }
                    consecutiveFailures = 0
                    if (batch.status == "FAILED") {
                        logBus.warn("用户触摸录制失败：${batch.error ?: "unknown"}，回退 Shell")
                        break
                    }
                    processBatch(
                        events = batch.events,
                        inputSource = "shizuku",
                        newWriter = newWriter,
                        resolver = resolver,
                        loop = loop,
                        captureContext = options.captureContext,
                        sessionStartWall = sessionStartWall,
                        sessionStartUptime = sessionStartUptime,
                        batchStartUptime = batch.startUptime,
                    )
                    if (batch.status == "STOPPED") break
                }
            }

            if (userScope?.isActive == true) {
                runShellInputLoop(
                    newWriter = newWriter,
                    resolver = resolver,
                    loop = loop,
                    captureContext = options.captureContext,
                )
            }
        } finally {
            // 即使启动调用被取消 / 抛异常，也要确保特权进程停止采集，避免后台一直抓触摸。
            runCatching { privilegedClient?.stopInteractionRecord() }
        }
    }

    private suspend fun processBatch(
        events: List<JSONObject>,
        inputSource: String,
        newWriter: RecordingWriter,
        resolver: RecordingContextResolver,
        loop: RecordingCaptureLoop?,
        captureContext: Boolean,
        sessionStartWall: Long,
        sessionStartUptime: Long,
        batchStartUptime: Long,
    ) {
        if (events.isEmpty()) return
        val packageName = resolver.foreground()
        val previousPackage = resolver.foregroundChanged(packageName)
        val nowUptime = SystemClock.uptimeMillis()
        val nowWall = System.currentTimeMillis()
        val parsed = buildList {
            events.forEach { json ->
                val kind = UserActionKind.from(json.optString("kind")) ?: return@forEach
                val points = json.optJSONArray("points")
                val tMs = points?.optJSONArray(0)?.optLong(2) ?: 0L
                val wall = resolveEventWall(
                    nowWall = nowWall,
                    nowUptime = nowUptime,
                    sessionStartWall = sessionStartWall,
                    sessionStartUptime = sessionStartUptime,
                    batchStartUptime = batchStartUptime,
                    tMs = tMs,
                )
                val timestamp = newWriter.timestampOf(wall)
                val screen = json.optJSONObject("screen")
                add(
                    UserActionEvent(
                        timestamp = timestamp,
                        kind = kind,
                        x = json.optInt("x"),
                        y = json.optInt("y"),
                        x2 = json.optInt("x2"),
                        y2 = json.optInt("y2"),
                        nx = json.optDouble("nx"),
                        ny = json.optDouble("ny"),
                        nx2 = json.optDouble("nx2"),
                        ny2 = json.optDouble("ny2"),
                        durationMs = json.optLong("durationMs"),
                        screenWidth = screen?.optInt("w") ?: 0,
                        screenHeight = screen?.optInt("h") ?: 0,
                        rotation = screen?.optInt("rotation") ?: 0,
                        packageName = packageName,
                        targets = emptyList(),
                        beforeFrame = loop?.frameBefore(timestamp),
                        inputSource = inputSource,
                        strokes = json.optJSONArray("strokes"),
                        pointerCount = json.optInt("pointerCount", 1).coerceAtLeast(1),
                    ),
                )
            }
        }
        // 前台切换事件必须使用本批首个动作的时间，否则按 seq 排序后时间戳会倒退，
        // RecordingValidator 会把整条录制判成「事件时间戳倒退」。
        if (previousPackage != null) {
            newWriter.record(
                AppEvent(
                    timestamp = parsed.firstOrNull()?.timestamp ?: newWriter.timestamp(),
                    action = "switch:$previousPackage",
                    packageName = packageName,
                    success = true,
                    source = RecordSource.USER,
                ),
            )
        }
        parsed.forEach { event ->
            val seq = newWriter.record(event)
            enqueueContext(
                writer = newWriter,
                actionSeq = seq,
                x = event.x,
                y = event.y,
                packageName = packageName,
                rotation = event.rotation,
                captureContext = captureContext,
                resolver = resolver,
            )
            loop?.scheduleAfter(event.timestamp)
        }
    }

    private fun enqueueContext(
        writer: RecordingWriter,
        actionSeq: Long,
        x: Int,
        y: Int,
        packageName: String,
        rotation: Int,
        captureContext: Boolean,
        resolver: RecordingContextResolver,
    ) {
        if (!captureContext || actionSeq <= 0L) return
        val executor = contextExecutor ?: return
        runCatching {
            executor.execute {
                runCatching {
                    RecordingSuppression.withoutRecording { resolver.refreshIfStale() }
                    val targets = resolver.targetsAt(x, y)
                    writer.record(
                        ContextEvent(
                            timestamp = writer.timestamp(),
                            refSeq = actionSeq,
                            packageName = packageName,
                            targets = targets,
                            rotation = rotation,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun runShellInputLoop(
        newWriter: RecordingWriter,
        resolver: RecordingContextResolver,
        loop: RecordingCaptureLoop?,
        captureContext: Boolean,
    ) {
        val controller = controllers.controllerFor(Capability.SHELL)
        if (controller == null) {
            logBus.warn("用户触摸录制：没有可用的 Shell 后端，录制仅保留脚本动作")
            newWriter.setInputSource("none")
            return
        }
        // 采集用的诊断命令按线程作用域抑制，避免写进时间线；不要用命令前缀做全局黑名单，
        // 那会连脚本自己发起的同名 Shell 动作一起吞掉。
        val deviceOutput = RecordingSuppression.withoutRecording {
            controller.shell("getevent -lp")
        }?.stdout
        val device = deviceOutput
            ?.let { UnlockRecordingParser.parseTouchDevices(it) }
            ?.firstOrNull { candidate ->
                RecordingSuppression.withoutRecording {
                    controller.shell("test -r '${candidate.path}'")
                }?.exitCode == 0
            }
        if (device == null) {
            logBus.warn("用户触摸录制：找不到可读触摸设备，录制仅保留脚本动作")
            newWriter.setInputSource("none")
            return
        }
        newWriter.setInputSource("shell")
        val hasTimeout = runCatching {
            RecordingSuppression.withoutRecording {
                controller.shell("command -v timeout >/dev/null 2>&1; echo \$?")
            }?.stdout?.trim() == "0"
        }.getOrDefault(false)
        while (userScope?.isActive == true) {
            val commandStartedWall = System.currentTimeMillis()
            val commandStartedUptime = SystemClock.uptimeMillis()
            val cmd = if (hasTimeout) {
                "timeout 3 getevent -lt '${device.path}'"
            } else {
                "getevent -c 200 -lt '${device.path}'"
            }
            val output = RecordingSuppression.withoutRecording {
                controller.shell(cmd)
            }?.stdout ?: ""
            val parsed = runCatching {
                UnlockRecordingParser.parseStrokesWithBase(output, device)
            }.getOrNull()
            val strokes = parsed?.strokes.orEmpty()
            if (strokes.isNotEmpty()) {
                val packageName = resolver.foreground()
                strokes.forEach { stroke ->
                    emitShellStroke(
                        stroke = stroke,
                        device = device,
                        startedWall = commandStartedWall,
                        startedUptime = commandStartedUptime,
                        baseUptimeMs = parsed?.baseTimestampMs ?: -1L,
                        packageName = packageName,
                        newWriter = newWriter,
                        resolver = resolver,
                        loop = loop,
                        captureContext = captureContext,
                    )
                }
            }
            delay(SHELL_POLL_INTERVAL_MS)
        }
    }

    private fun emitShellStroke(
        stroke: UnlockStroke,
        device: TouchInputDevice,
        startedWall: Long,
        startedUptime: Long,
        baseUptimeMs: Long,
        packageName: String,
        newWriter: RecordingWriter,
        resolver: RecordingContextResolver,
        loop: RecordingCaptureLoop?,
        captureContext: Boolean,
    ) {
        if (stroke.points.isEmpty()) return
        val screen = screenGeometry()
        val naturalWidth = if (screen.rotation % 2 == 0) screen.width else screen.height
        val naturalHeight = if (screen.rotation % 2 == 0) screen.height else screen.width
        val mapped = stroke.points.map { point ->
            val nx = (point.x * naturalWidth).toInt().coerceIn(0, naturalWidth - 1)
            val ny = (point.y * naturalHeight).toInt().coerceIn(0, naturalHeight - 1)
            naturalToLogical(nx, ny, naturalWidth, naturalHeight, screen.rotation)
        }
        val first = mapped.first()
        val last = mapped.last()
        val duration = stroke.durationMs.coerceAtLeast(1L)
        val distance = hypot((last.first - first.first).toDouble(), (last.second - first.second).toDouble())
        val kind = when {
            distance <= TAP_SLOP && duration < LONG_PRESS_MS -> UserActionKind.TAP
            distance <= TAP_SLOP -> UserActionKind.LONG_CLICK
            else -> UserActionKind.SWIPE
        }
        // 用命令启动的 uptime 对齐，避免把 shell 进程启动延迟算进事件时间。
        val nowUptime = SystemClock.uptimeMillis()
        val nowWall = System.currentTimeMillis()
        // stroke.startTimeMs 相对本批首个事件；优先用内核时间戳还原绝对 uptime。
        val eventUptime = if (baseUptimeMs > 0L) {
            baseUptimeMs + stroke.startTimeMs
        } else {
            startedUptime + stroke.startTimeMs
        }
        val wall = if (startedUptime > 0L && eventUptime <= nowUptime) {
            nowWall - (nowUptime - eventUptime)
        } else {
            startedWall + stroke.startTimeMs
        }
        val timestamp = newWriter.timestampOf(wall)
        val event = UserActionEvent(
            timestamp = timestamp,
            kind = kind,
            x = first.first,
            y = first.second,
            x2 = last.first,
            y2 = last.second,
            nx = first.first.toDouble() / screen.width.coerceAtLeast(1),
            ny = first.second.toDouble() / screen.height.coerceAtLeast(1),
            nx2 = last.first.toDouble() / screen.width.coerceAtLeast(1),
            ny2 = last.second.toDouble() / screen.height.coerceAtLeast(1),
            durationMs = duration,
            screenWidth = screen.width,
            screenHeight = screen.height,
            rotation = screen.rotation,
            packageName = packageName,
            targets = emptyList(),
            beforeFrame = loop?.frameBefore(timestamp),
            inputSource = "shell",
            pointerCount = 1,
        )
        val seq = newWriter.record(event)
        enqueueContext(
            writer = newWriter,
            actionSeq = seq,
            x = first.first,
            y = first.second,
            packageName = packageName,
            rotation = screen.rotation,
            captureContext = captureContext,
            resolver = resolver,
        )
        loop?.scheduleAfter(timestamp)
    }

    private fun naturalToLogical(
        nx: Int,
        ny: Int,
        naturalWidth: Int,
        naturalHeight: Int,
        rotation: Int,
    ): Pair<Int, Int> = when (rotation) {
        1 -> ny to (naturalWidth - 1 - nx)
        2 -> (naturalWidth - 1 - nx) to (naturalHeight - 1 - ny)
        3 -> (naturalHeight - 1 - ny) to nx
        else -> nx to ny
    }

    private fun screenGeometry(): ScreenGeometry {
        val metrics = DisplayMetrics()
        val display = runCatching {
            context.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull()
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        } else {
            metrics.setTo(context.resources.displayMetrics)
        }
        val rotation = runCatching { display?.rotation ?: 0 }.getOrDefault(0)
        return ScreenGeometry(metrics.widthPixels, metrics.heightPixels, rotation)
    }

    private fun deviceInfo(): JSONObject {
        val screen = screenGeometry()
        return JSONObject()
            .put("width", screen.width)
            .put("height", screen.height)
            .put("rotation", screen.rotation)
            .put("model", Build.MODEL.orEmpty())
    }

    private fun appVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private data class ScreenGeometry(val width: Int, val height: Int, val rotation: Int)

    private companion object {
        const val POLL_INTERVAL_MS = 250L
        const val SHELL_POLL_INTERVAL_MS = 50L
        const val MAX_POLL_FAILURES = 8
        const val TAP_SLOP = 24.0
        const val LONG_PRESS_MS = 500L
    }
}
