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
