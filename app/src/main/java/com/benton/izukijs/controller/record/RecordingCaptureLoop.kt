package com.benton.izukijs.controller.record

import android.graphics.Bitmap
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.service.OverlayCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

data class FrameSample(val timestamp: Long, val path: String)

/**
 * 用户录制期间的截图采样器。
 *
 * 以固定间隔抓帧并保存到 [RecordingWriter]，同时维护最近若干帧的时间戳索引。
 * 用户动作发生时用 [frameBefore] 取动作前最近一帧，作为 `beforeFrame` 写入事件。
 */
class RecordingCaptureLoop(
    private val writer: RecordingWriter,
    private val intervalMs: Long,
    private val logBus: LogBus,
    private val captureProvider: () -> Bitmap?,
    private val onCaptureFailure: () -> Unit = {},
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val afterLock = Any()
    private val samples = ArrayDeque<FrameSample>()
    private val afterJobs = mutableSetOf<Job>()
    private var job: Job? = null

    @Volatile
    private var stopped = false

    @Volatile
    private var acceptingAfter = true

    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    private var failureReported = false

    fun start() {
        if (job != null) return
        stopped = false
        acceptingAfter = true
        job = scope.launch {
            // 先抓一帧，保证第一下点击也有可用的“动作前”画面。
            captureOnce()
            while (isActive && !stopped) {
                delay(intervalMs.coerceAtLeast(MIN_INTERVAL_MS))
                captureOnce()
            }
        }
    }

    fun stop() {
        // 先停止采样，但保留一小段窗口让已排队的动作后补帧完成，避免 stop 前最后一击丢帧。
        val sampling = job
        sampling?.cancel()
        job = null
        acceptingAfter = false
        val pending = synchronized(afterLock) {
            buildList {
                addAll(afterJobs)
                sampling?.let(::add)
            }
        }
        if (pending.isNotEmpty()) {
            runCatching {
                runBlocking {
                    withTimeoutOrNull(STOP_GRACE_MS) { pending.joinAll() }
                }
            }
        }
        stopped = true
        scope.cancel()
        synchronized(afterLock) { afterJobs.clear() }
        synchronized(lock) { samples.clear() }
    }

    /**
     * 返回动作时间点之前最近的一帧。
     *
     * 采样队列只保留最近 [MAX_SAMPLES] 帧。找不到动作前的帧时返回 null，
     * 不再用动作之后的画面冒充 beforeFrame。
     */
    fun frameBefore(timestamp: Long): String? = synchronized(lock) {
        samples.lastOrNull { it.timestamp <= timestamp }?.path
    }

    /** 动作后补一帧，捕捉页面跳转后的画面；[actionSeq] 用于把帧回填到动作。 */
    fun scheduleAfter(timestamp: Long, actionSeq: Long? = null) {
        if (stopped || !acceptingAfter) return
        if (writer.frameCapReached()) {
            writer.markFrameCapReached()
            return
        }
        val job = scope.launch {
            delay(AFTER_DELAY_MS)
            // 如果采样线程已经抓到了足够晚的帧，直接复用，避免重复截图。
            val existing = synchronized(lock) {
                samples.lastOrNull { it.timestamp >= timestamp + AFTER_DELAY_MS }
            }
            val sample = existing ?: captureOnce() ?: return@launch
            if (actionSeq != null && actionSeq > 0L) {
                writer.record(
                    ContextEvent(
                        timestamp = sample.timestamp,
                        refSeq = actionSeq,
                        afterFrame = sample.path,
                    ),
                )
            }
        }
        synchronized(afterLock) { afterJobs.add(job) }
        job.invokeOnCompletion {
            synchronized(afterLock) { afterJobs.remove(job) }
        }
    }

    private fun captureOnce(): FrameSample? {
        if (stopped) return null
        if (writer.frameCapReached()) {
            writer.markFrameCapReached()
            return null
        }
        val startedWall = System.currentTimeMillis()
        val bitmap = runCatching {
            OverlayCoordinator.withoutOverlay { captureProvider() }
        }.getOrElse {
            noteCaptureFailure(it.message)
            return null
        } ?: run {
            noteCaptureFailure(null)
            return null
        }
        val elapsed = (System.currentTimeMillis() - startedWall).coerceAtLeast(0L)
        try {
            val timestamp = writer.timestampOf(startedWall)
            val path = writer.recordScreencap(
                bitmap = bitmap,
                atMs = timestamp,
                elapsedMs = elapsed,
                source = RecordSource.USER,
            )
            if (path != null) {
                consecutiveFailures = 0
                failureReported = false
                val sample = FrameSample(timestamp, path)
                synchronized(lock) {
                    samples.addLast(sample)
                    while (samples.size > MAX_SAMPLES) samples.removeFirst()
                }
                return sample
            }
            return null
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    private fun noteCaptureFailure(message: String?) {
        if (message != null) logBus.debug("用户录制截图失败: $message")
        consecutiveFailures++
        if (consecutiveFailures >= FAILURE_REPORT_THRESHOLD && !failureReported) {
            failureReported = true
            onCaptureFailure()
        }
    }

    private companion object {
        const val MIN_INTERVAL_MS = 250L
        const val AFTER_DELAY_MS = 220L
        const val MAX_SAMPLES = 12
        const val STOP_GRACE_MS = 350L
        const val FAILURE_REPORT_THRESHOLD = 3
    }
}
