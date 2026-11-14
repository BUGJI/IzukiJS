package com.benton.izukijs.controller.record

import android.content.Context
import android.graphics.Bitmap
import com.benton.izukijs.core.storage.AtomicFile
import com.benton.izukijs.developer.DeveloperTuning
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

data class RecordingMeta(
    val id: String = "",
    val name: String = "",
    val createdAt: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val frameCount: Int = 0,
    val eventCount: Int = 0,
    val durationMs: Long = 0L,
    val truncated: Boolean = false,
    /** `script`（脚本 / MCP 动作）或 `user`（用户真实触摸）。 */
    val kind: String = KIND_SCRIPT,
    /** 用户输入采集来源：shizuku / shell。 */
    val inputSource: String = "",
    /** 是否成功采集到画面帧（屏幕录制未授权时为 false）。 */
    val framesAvailable: Boolean = true,
    /** 用户录制时的截图采样间隔。 */
    val sampleIntervalMs: Long = 0L,
    val version: Int = RecordFormat.VERSION,
    val device: JSONObject = JSONObject(),
    val options: JSONObject = JSONObject(),
    /** 是否由 [RecordingWriter.close] 正常收尾；进程被杀后为 false，读取时按事件文件重建统计。 */
    val complete: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("createdAt", createdAt)
        .put("width", width)
        .put("height", height)
        .put("frameCount", frameCount)
        .put("eventCount", eventCount)
        .put("durationMs", durationMs)
        .put("truncated", truncated)
        .put("kind", kind)
        .put("inputSource", inputSource)
        .put("framesAvailable", framesAvailable)
        .put("sampleIntervalMs", sampleIntervalMs)
        .put("version", version)
        .put("device", device)
        .put("options", options)
        .put("complete", complete)

    companion object {
        const val KIND_SCRIPT = "script"
        const val KIND_USER = "user"

        fun fromJson(json: JSONObject): RecordingMeta? {
            val id = json.optString("id")
            if (id.isBlank()) return null
            return RecordingMeta(
                id = id,
                name = json.optString("name", id),
                createdAt = json.optLong("createdAt"),
                width = json.optInt("width"),
                height = json.optInt("height"),
                frameCount = json.optInt("frameCount"),
                eventCount = json.optInt("eventCount"),
                durationMs = json.optLong("durationMs"),
                truncated = json.optBoolean("truncated"),
                kind = json.optString("kind", KIND_SCRIPT).ifBlank { KIND_SCRIPT },
                inputSource = json.optString("inputSource"),
                framesAvailable = json.optBoolean("framesAvailable", true),
                sampleIntervalMs = json.optLong("sampleIntervalMs"),
                version = json.optInt("version", 1).coerceAtLeast(1),
                device = json.optJSONObject("device") ?: JSONObject(),
                options = json.optJSONObject("options") ?: JSONObject(),
                // 旧版本没有该字段：视为已正常收尾，避免每次列表都重扫事件文件。
                complete = json.optBoolean("complete", true),
            )
        }
    }
}

/** 录制条目：元信息 + 目录占用，供设置页展示。 */
data class RecordingEntry(
    val meta: RecordingMeta,
    val sizeBytes: Long,
)

/** 流式汇总，避免为了统计把整条录制读入内存。 */
data class RecordingSummary(
    val userActions: Int,
    val scriptActions: Int,
    val frames: Int,
    val packages: List<String>,
    val eventCount: Int,
    val durationMs: Long,
)

class RecordingStore(private val root: File) {

    constructor(context: Context) : this(File(context.filesDir, "recordings"))

    /** 目录大小按 2 秒 TTL 缓存，避免每次刷新列表都遍历所有帧文件。 */
    private val sizeCache = java.util.concurrent.ConcurrentHashMap<String, CachedSize>()

    private data class CachedSize(val atMs: Long, val bytes: Long)

    /** 崩溃 / 未收尾录制的派生元信息缓存；键为目录，版本取事件文件大小+修改时间。 */
    private val derivedMetaCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Long, RecordingMeta>>()

    fun create(
        name: String,
        kind: String = RecordingMeta.KIND_SCRIPT,
        inputSource: String = "",
        framesAvailable: Boolean = false,
        sampleIntervalMs: Long = 0L,
        device: JSONObject = JSONObject(),
        options: JSONObject = JSONObject(),
    ): RecordingWriter {
        root.mkdirs()
        val id = newId()
        val dir = File(root, id)
        dir.mkdirs()
        val meta = RecordingMeta(
            id = id,
            name = name.trim().ifBlank { "recording" }.take(MAX_NAME_CHARS),
            createdAt = System.currentTimeMillis(),
            kind = kind,
            inputSource = inputSource,
            framesAvailable = framesAvailable,
            sampleIntervalMs = sampleIntervalMs,
            version = RecordFormat.VERSION,
            device = device,
            options = options,
            complete = false,
        )
        writeMeta(dir, meta)
        val tuning = DeveloperTuning.current
        return RecordingWriter(
            dir = dir,
            meta = meta,
            maxUniqueFrames = tuning.recordingMaxUniqueFrames.coerceAtLeast(1),
            frameQuality = tuning.recordingFrameQuality.coerceIn(50, 100),
            frameMaxDimension = tuning.recordingFrameMaxDimension.coerceAtLeast(0),
        )
    }

    fun list(): List<RecordingMeta> {
        val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        return dirs.mapNotNull { metaOf(it) }
            .sortedByDescending { it.createdAt }
    }

    /** 删除后更新名称；返回是否写入成功。 */
    fun rename(id: String, name: String): Boolean {
        val dir = safeDir(id) ?: return false
        val meta = metaOf(dir) ?: return false
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false
        val updated = meta.copy(name = trimmed.take(MAX_NAME_CHARS))
        return writeMeta(dir, updated)
    }

    fun entries(): List<RecordingEntry> = list().map { meta ->
        RecordingEntry(meta, sizeBytes(meta.id))
    }

    fun sizeBytes(id: String): Long {
        val dir = safeDir(id) ?: return 0L
        val now = System.currentTimeMillis()
        sizeCache[id]?.takeIf { now - it.atMs < SIZE_CACHE_TTL_MS }?.let { return it.bytes }
        val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        sizeCache[id] = CachedSize(now, bytes)
        return bytes
    }

    fun totalSizeBytes(): Long {
        if (!root.isDirectory) return 0L
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun info(id: String): RecordingMeta? {
        val dir = safeDir(id) ?: return null
        return metaOf(dir)
    }

    fun load(id: String): RecordingReader? {
        val dir = safeDir(id) ?: return null
        val eventsFile = File(dir, EVENTS_FILE)
        if (!eventsFile.isFile) return null
        val rawEvents = ArrayList<RecordEvent>()
        eventsFile.bufferedReader().use { reader ->
            reader.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                runCatching { RecordEvent.fromJson(JSONObject(line)) }.getOrNull()?.let(rawEvents::add)
            }
        }
        val meta = reconcile(readMeta(dir), rawEvents)

        // 旧版本用户录制的时间戳可能为 0；用动作前截图的时间回填，至少保证时间线可读。
        val frameTimes = rawEvents.filterIsInstance<ScreenshotEvent>().associate { it.frame to it.timestamp }
        val normalized = rawEvents.map { event ->
            if (event is UserActionEvent && event.timestamp <= 0L) {
                val fallback = event.beforeFrame?.let(frameTimes::get) ?: 0L
                if (fallback > 0L) event.copy(timestamp = fallback) else event
            } else {
                event
            }
        }
        val merged = mergeContexts(normalized)
        val ordered = if (merged.any { it.seq > 0L }) {
            merged.sortedBy { it.seq }
        } else {
            merged.sortedBy { it.timestamp }
        }
        return RecordingReader(meta ?: derivedMeta(dir.name, ordered) ?: return null, dir, ordered)
    }

    /** 流式汇总，供 MCP `recording info` 使用，避免把整条录制读入内存。 */
    fun summary(id: String): RecordingSummary? {
        val dir = safeDir(id) ?: return null
        val eventsFile = File(dir, EVENTS_FILE)
        if (!eventsFile.isFile) return null
        var userActions = 0
        var scriptActions = 0
        var frames = 0
        var events = 0
        var first = Long.MAX_VALUE
        var last = 0L
        val packages = LinkedHashSet<String>()
        eventsFile.bufferedReader().use { reader ->
            reader.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                val event = runCatching { RecordEvent.fromJson(JSONObject(line)) }
                    .getOrNull() ?: return@forEach
                events++
                if (event !is ContextEvent) {
                    val end = event.timestamp + event.elapsedMs
                    if (event.timestamp < first) first = event.timestamp
                    if (end > last) last = end
                }
                when (event) {
                    is UserActionEvent -> {
                        userActions++
                        if (event.packageName.isNotBlank()) packages.add(event.packageName)
                    }
                    is ScreenshotEvent -> frames++
                    is SessionStartEvent, is ContextEvent -> Unit
                    is AppEvent -> {
                        scriptActions++
                        if (event.packageName.isNotBlank()) packages.add(event.packageName)
                    }
                    else -> scriptActions++
                }
            }
        }
        return RecordingSummary(
            userActions = userActions,
            scriptActions = scriptActions,
            frames = frames,
            packages = packages.toList(),
            eventCount = events,
            durationMs = if (first == Long.MAX_VALUE) 0L else (last - first).coerceAtLeast(0L),
        )
    }

    /**
     * 元信息优先，但未正常收尾或缺失时按事件文件重建统计，避免崩溃后列表一直显示 0。
     */
    private fun metaOf(dir: File): RecordingMeta? {
        val meta = readMeta(dir)
        if (meta != null && meta.complete) return meta
        val eventsFile = File(dir, EVENTS_FILE)
        if (!eventsFile.isFile) return meta
        val stamp = eventsFile.length() * 31 + eventsFile.lastModified()
        val cached = derivedMetaCache[dir.absolutePath]
        if (cached != null && cached.first == stamp) return merge(meta, cached.second)
        val derived = deriveMeta(dir) ?: return meta
        derivedMetaCache[dir.absolutePath] = stamp to derived
        return merge(meta, derived)
    }

    private fun reconcile(meta: RecordingMeta?, events: List<RecordEvent>): RecordingMeta? {
        val derived = derivedMeta(meta?.id ?: "", events) ?: return meta
        return merge(meta, derived)
    }

    private fun merge(meta: RecordingMeta?, derived: RecordingMeta): RecordingMeta {
        if (meta == null) return derived
        return meta.copy(
            width = derived.width.takeIf { it > 0 } ?: meta.width,
            height = derived.height.takeIf { it > 0 } ?: meta.height,
            frameCount = derived.frameCount,
            eventCount = derived.eventCount,
            durationMs = derived.durationMs,
            truncated = meta.truncated || derived.truncated,
            framesAvailable = derived.framesAvailable,
            inputSource = meta.inputSource.ifBlank { derived.inputSource },
            kind = if (meta.kind == RecordingMeta.KIND_USER || derived.kind == RecordingMeta.KIND_USER) {
                RecordingMeta.KIND_USER
            } else {
                meta.kind
            },
        )
    }

    /** 把异步补全的 context 事件合并回对应动作；context 事件本身保留，供 timeline 过滤。 */
    private fun mergeContexts(events: List<RecordEvent>): List<RecordEvent> {
        // 同一动作可能有多条 context：UI 上下文与动作后补帧分两次写入。
        val contexts = events.filterIsInstance<ContextEvent>().groupBy { it.refSeq }
        if (contexts.isEmpty()) return events
        return events.map { event ->
            if (event is UserActionEvent && event.seq > 0L) {
                val parts = contexts[event.seq] ?: return@map event
                event.copy(
                    packageName = event.packageName.ifBlank {
                        parts.firstNotNullOfOrNull { it.packageName.ifBlank { null } }.orEmpty()
                    },
                    targets = if (event.targets.isEmpty()) {
                        parts.firstNotNullOfOrNull { it.targets.ifEmpty { null } }.orEmpty()
                    } else {
                        event.targets
                    },
                    rotation = if (event.rotation == 0) {
                        parts.firstNotNullOfOrNull { it.rotation.takeIf { r -> r != 0 } } ?: 0
                    } else {
                        event.rotation
                    },
                    afterFrame = event.afterFrame
                        ?: parts.firstNotNullOfOrNull { it.afterFrame },
                )
            } else {
                event
            }
        }
    }

    fun delete(id: String): Boolean {
        val dir = safeDir(id) ?: return false
        val deleted = dir.deleteRecursively()
        if (deleted) {
            sizeCache.remove(id)
            derivedMetaCache.remove(dir.absolutePath)
        }
        return deleted
    }

    /** 删除全部录制；返回成功删除的目录数。 */
    fun deleteAll(): Int {
        val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        sizeCache.clear()
        derivedMetaCache.clear()
        return dirs.count { it.deleteRecursively() }
    }

    fun path(id: String): File? = safeDir(id)

    private fun safeDir(id: String): File? {
        if (!ID_REGEX.matches(id) || id == "." || id == "..") return null
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val dir = File(base, id)
        val canonical = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        // 防止符号链接或「..」把删除 / 读取范围逃逸到录制目录之外。
        if (canonical.parentFile != base) return null
        return canonical.takeIf { it.isDirectory }
    }

    private fun readMeta(dir: File): RecordingMeta? {
        val file = File(dir, META_FILE)
        if (!file.isFile) return null
        return runCatching { RecordingMeta.fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    private fun deriveMeta(dir: File): RecordingMeta? {
        val eventsFile = File(dir, EVENTS_FILE)
        if (!eventsFile.isFile) return null
        val accumulator = MetaAccumulator(dir.name)
        eventsFile.bufferedReader().use { reader ->
            reader.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                runCatching { RecordEvent.fromJson(JSONObject(line)) }
                    .getOrNull()
                    ?.let(accumulator::add)
            }
        }
        return accumulator.toMeta()?.copy(
            name = dir.name,
            createdAt = dir.lastModified(),
        )
    }

    private fun derivedMeta(id: String, events: List<RecordEvent>): RecordingMeta? {
        val accumulator = MetaAccumulator(id)
        events.forEach(accumulator::add)
        return accumulator.toMeta()
    }

    /** 单遍累加统计，既用于已知事件列表，也用于流式重扫崩溃的录制。 */
    private class MetaAccumulator(private val id: String) {
        private var eventCount = 0
        private var width = 0
        private var height = 0
        private var first = Long.MAX_VALUE
        private var last = 0L
        private var userInputSource = ""
        private val frames = HashSet<String>()

        fun add(event: RecordEvent) {
            eventCount++
            // context 事件是动作后异步补全的，计入时长会虚增长度。
            if (event !is ContextEvent) {
                val end = event.timestamp + event.elapsedMs
                if (event.timestamp < first) first = event.timestamp
                if (end > last) last = end
            }
            if (event is ScreenshotEvent) {
                frames.add(event.frame)
                if (width == 0) {
                    width = event.width
                    height = event.height
                }
            }
            if (event is UserActionEvent && userInputSource.isBlank()) {
                userInputSource = event.inputSource
            }
        }

        fun toMeta(): RecordingMeta? {
            if (eventCount == 0) return null
            return RecordingMeta(
                id = id,
                name = id,
                width = width,
                height = height,
                frameCount = frames.size,
                eventCount = eventCount,
                durationMs = if (first == Long.MAX_VALUE) 0L else (last - first).coerceAtLeast(0L),
                kind = if (userInputSource.isNotBlank()) {
                    RecordingMeta.KIND_USER
                } else {
                    RecordingMeta.KIND_SCRIPT
                },
                inputSource = userInputSource,
                framesAvailable = frames.isNotEmpty(),
                complete = false,
            )
        }
    }

    private fun writeMeta(dir: File, meta: RecordingMeta): Boolean =
        runCatching {
            AtomicFile.writeText(File(dir, META_FILE), meta.toJson().toString())
            true
        }.getOrDefault(false)

    private fun newId(): String {
        val base = System.currentTimeMillis().toString(36)
        repeat(8) {
            val id = "$base-${Random.nextInt(1000, 9999)}"
            if (!File(root, id).exists()) return id
        }
        return "$base-${Random.nextInt(10000, 99999)}"
    }

    internal fun writeMetaFor(dir: File, meta: RecordingMeta): Boolean = writeMeta(dir, meta)

    private companion object {
        const val META_FILE = "meta.json"
        const val EVENTS_FILE = "events.jsonl"
        const val MAX_NAME_CHARS = 80
        const val SIZE_CACHE_TTL_MS = 2_000L
        val ID_REGEX = Regex("^[A-Za-z0-9._-]+$")
    }
}

class RecordingWriter internal constructor(
    private val dir: File,
    @Volatile private var meta: RecordingMeta,
    private val maxUniqueFrames: Int = DEFAULT_MAX_UNIQUE_FRAMES,
    private val frameQuality: Int = DEFAULT_FRAME_QUALITY,
    private val frameMaxDimension: Int = DEFAULT_FRAME_MAX_DIMENSION,
) {
    private val lock = Any()
    private val ioLock = Any()
    private val metaLock = Any()

    /** 帧去重 / 压缩串行锁：与 [lock] 分离，避免 JPEG 压缩阻塞事件入队与 close()。 */
    private val frameLock = Any()
    private val framesDir = File(dir, FRAMES_DIR).apply { mkdirs() }
    private val eventsFile = File(dir, EVENTS_FILE)
    private val output = BufferedWriter(FileWriter(eventsFile, true))
    private val startedAtWall = System.currentTimeMillis()
    private val seqCounter = AtomicLong(0L)

    /**
     * 所有事件 / 截图写入都经过单线程队列：截图压缩与 JSONL 落盘不阻塞脚本线程，
     * 同时保持动作与截图的录制顺序。
     */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "izuki-recording-writer").apply { isDaemon = true }
    }
    private val pendingScreenshots = AtomicInteger()

    @Volatile
    private var closed = false

    @Volatile
    private var overflowTruncated = false

    @Volatile
    private var eventCount = 0

    @Volatile
    private var uniqueFrames = 0

    @Volatile
    private var lastEventAtMs = 0L

    @Volatile
    private var lastEventElapsedMs = 0L

    private var lastFramePath: String? = null
    private var lastSampleHash: Int = 0
    private var lastWidth = 0
    private var lastHeight = 0

    /** 页面切换后强制写一张新帧，避免画面片段相同被去重成上一页。 */
    @Volatile
    private var forceNextFrame = false

    @Volatile
    private var frameCapMarked = false

    val id: String get() = meta.id

    /** 唯一截图帧是否已到上限；采样线程据此停止继续抓帧。 */
    fun frameCapReached(): Boolean = uniqueFrames >= maxUniqueFrames

    /** 采样线程因帧上限停止时显式标记截断，避免录制看起来仍然完整。 */
    fun markFrameCapReached() {
        if (frameCapMarked) return
        frameCapMarked = true
        updateMeta { it.copy(truncated = true) }
    }

    /** 页面切换后调用：下一帧即使与上一帧相似也必须重新写盘。 */
    fun requestFreshFrame() {
        forceNextFrame = true
    }

    /**
     * 帧数达到上限后写入一条复用最后一帧的 `screencap` 事件。
     *
     * 不保存新文件，只保留时间线连续性；调用方不能把它加入采样索引，
     * 否则后续动作会再次被标记成上限之前的旧页面。
     */
    fun recordCappedFrame(
        atMs: Long,
        elapsedMs: Long = 0L,
        source: String = RecordSource.USER,
    ) {
        markFrameCapReached()
        val frame = synchronized(frameLock) {
            val path = lastFramePath ?: return
            Triple(path, lastWidth, lastHeight)
        }
        synchronized(lock) {
            if (closed) return
            val seq = seqCounter.incrementAndGet()
            worker.execute {
                writeEvent(
                    ScreenshotEvent(
                        timestamp = atMs,
                        frame = frame.first,
                        width = frame.second,
                        height = frame.third,
                        stale = true,
                        source = source,
                        elapsedMs = elapsedMs,
                    ),
                    seq,
                )
            }
        }
    }

    fun timestamp(): Long = timestampOf(System.currentTimeMillis())

    /** 把绝对墙钟时间换算成录制内相对时间，供外部采集线程对齐事件与截图。 */
    fun timestampOf(wallMs: Long): Long = (wallMs - startedAtWall).coerceAtLeast(0L)

    /** 用户输入后端在录制开始后才确定（Shizuku / Shell），这里补写 meta。 */
    fun setInputSource(value: String) {
        updateMeta { it.copy(inputSource = value) }
    }

    /** 写一条会话头，记录版本、设备与录制选项。 */
    fun writeSessionStart(appVersion: String, device: JSONObject, options: JSONObject) {
        record(
            SessionStartEvent(
                timestamp = timestamp(),
                appVersion = appVersion,
                device = device,
                options = options,
            ),
        )
    }

    /** 返回本次事件的单调序号；已关闭时返回 -1。 */
    fun record(event: RecordEvent): Long {
        synchronized(lock) {
            if (closed) return -1L
            // 序号与入队在同一把锁内分配，保证 seq 顺序 = JSONL 写入顺序。
            val seq = seqCounter.incrementAndGet()
            worker.execute { writeEvent(event, seq) }
            return seq
        }
    }

    /**
     * 异步记录一张截图；不等待 JPEG 压缩，适合脚本 / 流水线的截屏热路径。
     */
    fun recordScreencapAsync(
        bitmap: Bitmap?,
        atMs: Long,
        elapsedMs: Long = 0L,
        source: String = RecordSource.SCRIPT,
    ) {
        enqueueScreencap(bitmap, atMs, elapsedMs, source, wait = false)
    }

    /**
     * 记录一张截图并返回相对路径。
     *
     * 写入与事件共用同一队列，保证 JSONL 顺序；调用方会等待压缩完成，
     * 因此用户录制的采样线程可以立刻把路径写进动作事件。
     */
    fun recordScreencap(
        bitmap: Bitmap?,
        atMs: Long,
        elapsedMs: Long = 0L,
        source: String = RecordSource.SCRIPT,
    ): String? = enqueueScreencap(bitmap, atMs, elapsedMs, source, wait = true)

    private fun enqueueScreencap(
        bitmap: Bitmap?,
        atMs: Long,
        elapsedMs: Long,
        source: String,
        wait: Boolean,
    ): String? {
        if (bitmap == null || bitmap.isRecycled) return null
        synchronized(lock) {
            if (closed) return null
            if (pendingScreenshots.incrementAndGet() > MAX_PENDING_SCREENSHOTS) {
                pendingScreenshots.decrementAndGet()
                overflowTruncated = true
                return null
            }
        }
        // 调用方在本方法返回后会 recycle 原图，必须先复制一份交给工作线程。
        val copy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        if (copy == null) {
            pendingScreenshots.decrementAndGet()
            return null
        }
        val result = AtomicReference<String?>()
        val latch = if (wait) CountDownLatch(1) else null
        synchronized(lock) {
            if (closed) {
                pendingScreenshots.decrementAndGet()
                runCatching { copy.recycle() }
                return null
            }
            // 序号必须在入队时分配，保证 JSONL 顺序与 seq 顺序一致：
            // 如果等到工作线程压缩完再分配，中间插入的动作会拿到更大的 seq。
            val seq = seqCounter.incrementAndGet()
            worker.execute {
                try {
                    result.set(saveFrame(copy, atMs, elapsedMs, source, seq))
                } finally {
                    runCatching { copy.recycle() }
                    pendingScreenshots.decrementAndGet()
                    latch?.countDown()
                }
            }
        }
        if (latch != null) {
            runCatching { latch.await(FRAME_WAIT_SECONDS, TimeUnit.SECONDS) }
        }
        return result.get()
    }

    /** 兼容旧调用：截图时间取写盘时刻。 */
    fun recordScreenshot(bitmap: Bitmap?) {
        recordScreencapAsync(bitmap, timestamp(), 0L)
    }

    /** 兼容旧调用：同步保存一帧并返回相对路径。 */
    fun captureFrame(bitmap: Bitmap?, wallMs: Long = System.currentTimeMillis()): String? =
        recordScreencap(bitmap, timestampOf(wallMs), 0L, RecordSource.USER)

    private fun saveFrame(
        bitmap: Bitmap,
        atMs: Long,
        elapsedMs: Long,
        source: String,
        seq: Long,
    ): String? {
        synchronized(frameLock) {
            val target = scaleForRecording(bitmap)
            try {
                val width = target.width
                val height = target.height
                val sample = sampleHash(target)
                val previousPath = lastFramePath
                // 页面切换后即使采样哈希相同也强制写新帧，防止新旧页面的公共区域导致误去重。
                val force = forceNextFrame
                forceNextFrame = false
                if (!force && previousPath != null && sample == lastSampleHash &&
                    width == lastWidth && height == lastHeight
                ) {
                    writeEvent(
                        ScreenshotEvent(
                            timestamp = atMs,
                            frame = previousPath,
                            width = width,
                            height = height,
                            // 画面与上一帧相同而复用旧图，标记为 stale 便于消费方区分。
                            stale = true,
                            source = source,
                            elapsedMs = elapsedMs,
                        ),
                        seq,
                    )
                    return previousPath
                }
                if (uniqueFrames >= maxUniqueFrames) {
                    updateMeta { it.copy(truncated = true) }
                    previousPath?.let {
                        writeEvent(
                            ScreenshotEvent(
                                timestamp = atMs,
                                frame = it,
                                width = width,
                                height = height,
                                stale = true,
                                source = source,
                                elapsedMs = elapsedMs,
                            ),
                            seq,
                        )
                    }
                    return previousPath
                }

                val index = uniqueFrames + 1
                val relative = "%s/frame_%05d.jpg".format(Locale.US, FRAMES_DIR, index)
                val file = File(dir, relative)
                val saved = runCatching {
                    file.outputStream().use {
                        target.compress(Bitmap.CompressFormat.JPEG, frameQuality, it)
                    }
                }.getOrDefault(false)
                if (!saved) return null

                uniqueFrames = index
                lastFramePath = relative
                lastSampleHash = sample
                lastWidth = width
                lastHeight = height
                updateMeta {
                    it.copy(
                        width = width,
                        height = height,
                        frameCount = uniqueFrames,
                        framesAvailable = true,
                    )
                }
                writeEvent(
                    ScreenshotEvent(
                        timestamp = atMs,
                        frame = relative,
                        width = width,
                        height = height,
                        source = source,
                        elapsedMs = elapsedMs,
                    ),
                    seq,
                )
                return relative
            } finally {
                if (target !== bitmap) runCatching { target.recycle() }
            }
        }
    }

    /** 按开发者选项限制录制帧最长边，减少长录制占用与解码压力。 */
    private fun scaleForRecording(bitmap: Bitmap): Bitmap {
        if (frameMaxDimension <= 0) return bitmap
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= frameMaxDimension) return bitmap
        val ratio = frameMaxDimension.toFloat() / longest
        val width = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(bitmap, width, height, true) }
            .getOrDefault(bitmap)
    }

    private fun writeEvent(event: RecordEvent, seq: Long) {
        synchronized(ioLock) {
            val wrote = runCatching {
                val json = event.toJson()
                    .put("v", RecordFormat.VERSION)
                    .put("seq", seq)
                output.write(json.toString())
                output.newLine()
            }.isSuccess
            if (!wrote) {
                // 磁盘写入失败时不要把事件计入 meta，否则统计会“看起来正常”但数据已丢。
                overflowTruncated = true
                return
            }
            eventCount++
            if (event.timestamp > lastEventAtMs) {
                lastEventAtMs = event.timestamp
                lastEventElapsedMs = event.elapsedMs
            } else if (event.timestamp == lastEventAtMs) {
                lastEventElapsedMs = max(lastEventElapsedMs, event.elapsedMs)
            }
            if (eventCount % META_FLUSH_EVERY == 0) {
                runCatching { output.flush() }
                persistMeta()
            }
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        // 等待可能正在同步写帧的采样线程，确保 flush 任务之后不会再有帧事件落盘。
        synchronized(frameLock) { }
        val latch = CountDownLatch(1)
        worker.execute {
            try {
                runCatching { output.flush() }
                runCatching { output.close() }
                // 只有走到这里才把录制标记为完整，进程被杀时保持 complete=false 以便读取时重建。
                persistMeta(forceComplete = true)
            } finally {
                latch.countDown()
            }
        }
        val completed = runCatching { latch.await(5, TimeUnit.SECONDS) }.getOrDefault(false)
        if (completed) {
            worker.shutdown()
        } else {
            // 压缩卡住时不无限等待；已排队任务由 shutdownNow 打断。
            worker.shutdownNow()
        }
    }

    private fun updateMeta(transform: (RecordingMeta) -> RecordingMeta) {
        synchronized(metaLock) {
            meta = transform(meta)
        }
    }

    private fun persistMeta(forceComplete: Boolean = false) {
        synchronized(ioLock) {
            val duration = if (lastEventAtMs > 0L) lastEventAtMs + lastEventElapsedMs else 0L
            updateMeta {
                it.copy(
                    frameCount = uniqueFrames,
                    eventCount = eventCount,
                    durationMs = duration,
                    truncated = it.truncated || overflowTruncated,
                    framesAvailable = uniqueFrames > 0,
                    complete = it.complete || forceComplete,
                )
            }
            val snapshot = synchronized(metaLock) { meta }
            runCatching { AtomicFile.writeText(File(dir, META_FILE), snapshot.toJson().toString()) }
        }
    }

    private fun sampleHash(bitmap: Bitmap): Int {
        var hash = 17
        hash = hash * 31 + bitmap.width
        hash = hash * 31 + bitmap.height
        val columns = SAMPLE_COLUMNS
        val rows = SAMPLE_ROWS
        for (row in 0 until rows) {
            val y = ((row + 0.5) * bitmap.height / rows).toInt().coerceIn(0, bitmap.height - 1)
            for (column in 0 until columns) {
                val x = ((column + 0.5) * bitmap.width / columns).toInt().coerceIn(0, bitmap.width - 1)
                hash = hash * 31 + bitmap.getPixel(x, y)
            }
        }
        return abs(hash)
    }

    private companion object {
        const val META_FLUSH_EVERY = 20
        const val DEFAULT_MAX_UNIQUE_FRAMES = 1_600
        const val DEFAULT_FRAME_QUALITY = 82
        const val DEFAULT_FRAME_MAX_DIMENSION = 960
        const val MAX_PENDING_SCREENSHOTS = 8
        const val FRAME_WAIT_SECONDS = 5L
        const val SAMPLE_COLUMNS = 64
        const val SAMPLE_ROWS = 64
        const val META_FILE = "meta.json"
        const val EVENTS_FILE = "events.jsonl"
        const val FRAMES_DIR = "frames"
    }
}

class RecordingReader(
    val meta: RecordingMeta,
    val dir: File,
    val events: List<RecordEvent>,
) {
    private val frameCache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
            if (size <= FRAME_CACHE_ENTRIES) return false
            runCatching { eldest.value.recycle() }
            return true
        }
    }

    /**
     * 返回调用方持有的副本；缓存本身保留解码结果，避免重复 JPEG 解码。
     *
     * [maxDimension] 大于 0 时按采样解码（用于缩略图），避免详情页把每帧全尺寸
     * 解码后同时留在内存里导致 OOM。
     */
    fun frame(event: ScreenshotEvent, maxDimension: Int = 0): Bitmap? {
        val key = if (maxDimension > 0) "$maxDimension:${event.frame}" else event.frame
        // 复制必须在缓存锁内完成：clearFrameCache / 替换缓存会回收原图，
        // 若把 copy 放到锁外，可能与回收并发并触发 "recycled bitmap"。
        synchronized(frameCache) {
            frameCache[key]
                ?.takeIf { !it.isRecycled }
                ?.let { cached ->
                    return runCatching { cached.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                }
        }

        val root = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        val file = runCatching { File(dir, event.frame).canonicalFile }.getOrNull() ?: return null
        if (!file.path.startsWith(root.path + File.separator)) return null
        val decoded = BitmapFactoryHelper.decode(file, maxDimension) ?: return null
        synchronized(frameCache) {
            frameCache[key]?.let { runCatching { it.recycle() } }
            frameCache[key] = decoded
            return runCatching { decoded.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        }
    }

    fun clearFrameCache() {
        synchronized(frameCache) {
            frameCache.values.forEach { runCatching { it.recycle() } }
            frameCache.clear()
        }
    }

    private companion object {
        const val FRAME_CACHE_ENTRIES = 8
    }
}

/** 根据目标最长边计算 JPEG 采样率（2 的幂），0 表示不采样。 */
internal fun recordingSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    if (maxDimension <= 0 || width <= 0 || height <= 0) return 1
    var sample = 1
    while (max(width, height) / (sample * 2) >= maxDimension) {
        sample *= 2
    }
    return sample
}

internal object BitmapFactoryHelper {
    fun decode(file: File, maxDimension: Int = 0): Bitmap? = runCatching {
        if (maxDimension <= 0) {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        } else {
            val bounds = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            val options = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = recordingSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        }
    }.getOrNull()
}

/**
 * 旧的线性游标：保留给现有回放调用与测试。
 *
 * 严格回放请使用 [RecordingReplay]，它会区分 screencap / 动作 / 上下文事件，
 * 并给出结构化差异。
 */
class RecordingCursor(private val events: List<RecordEvent>) {

    /** 供 [RecordingReplay] 兼容构造使用。 */
    internal val eventList: List<RecordEvent> get() = events

    private var index = 0
    val mismatches = ArrayList<String>()

    fun nextScreenshot(): ScreenshotEvent? {
        while (index < events.size) {
            val event = events[index++]
            if (event is ScreenshotEvent) return event
            val kind = RecordEvent.kindOf(event)
            if (kind != null) {
                mismatches.add("回放期望截图，但先遇到动作 $kind")
            }
        }
        return null
    }

    fun nextAction(kind: RecordActionKind?): RecordEvent? {
        while (index < events.size) {
            val event = events[index++]
            if (event is ScreenshotEvent) {
                mismatches.add("回放期望动作，但先遇到截图")
                continue
            }
            val actual = RecordEvent.kindOf(event) ?: continue
            if (kind == null || actual == kind) return event
            mismatches.add("回放期望 $kind，但遇到 $actual")
        }
        return null
    }

    fun remaining(): Int = (events.size - index).coerceAtLeast(0)
}
