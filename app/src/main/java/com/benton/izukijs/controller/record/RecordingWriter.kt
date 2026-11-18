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

class RecordingWriter internal constructor(
    private val dir: File,
    @Volatile private var meta: RecordingMeta,
    private val maxUniqueFrames: Int = DEFAULT_MAX_UNIQUE_FRAMES,
    private val frameQuality: Int = DEFAULT_FRAME_QUALITY,
    private val frameMaxDimension: Int = DEFAULT_FRAME_MAX_DIMENSION,
) : EventRecorder {
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
    override fun timestampOf(wallMs: Long): Long = (wallMs - startedAtWall).coerceAtLeast(0L)

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
    override fun record(event: RecordEvent): Long {
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
