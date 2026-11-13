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
import kotlinx.coroutines.launch

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
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val samples = ArrayDeque<FrameSample>()
    private var job: Job? = null

    @Volatile
    private var stopped = false

    fun start() {
        if (job != null) return
        stopped = false
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
        stopped = true
        job?.cancel()
        job = null
        scope.cancel()
        synchronized(lock) { samples.clear() }
    }

    /**
     * 返回动作时间点之前最近的一帧。
     *
     * 采样队列只保留最近 [MAX_SAMPLES] 帧，动作时间早于所有样本时不再乱退最早一帧；
     * 只有样本起点仅略晚于动作（容忍一个采样间隔内的时钟抖动）才回退，避免把
     * 动作之后的画面标成 beforeFrame。
     */
    fun frameBefore(timestamp: Long): String? = synchronized(lock) {
        samples.lastOrNull { it.timestamp <= timestamp }?.path
            ?: samples.firstOrNull { it.timestamp <= timestamp + FALLBACK_TOLERANCE_MS }?.path
    }

    /** 动作后补一帧，捕捉页面跳转后的画面；[actionSeq] 用于把帧回填到动作。 */
    fun scheduleAfter(timestamp: Long, actionSeq: Long? = null) {
        if (stopped) return
        if (writer.frameCapReached()) {
            writer.markFrameCapReached()
            return
        }
        scope.launch {
            delay(AFTER_DELAY_MS)
            val latest = synchronized(lock) { samples.lastOrNull()?.timestamp ?: 0L }
            if (latest <= timestamp) {
                val sample = captureOnce() ?: return@launch
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
            logBus.debug("用户录制截图失败: ${it.message}")
            null
        } ?: return null
        val elapsed = (System.currentTimeMillis() - startedWall).coerceAtLeast(0L)
        try {
            val path = writer.recordScreencap(
                bitmap = bitmap,
                atMs = writer.timestampOf(startedWall),
                elapsedMs = elapsed,
                source = RecordSource.USER,
            ) ?: return null
            val timestamp = writer.timestampOf(startedWall)
            val sample = FrameSample(timestamp, path)
            synchronized(lock) {
                samples.addLast(sample)
                while (samples.size > MAX_SAMPLES) samples.removeFirst()
            }
            return sample
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    private companion object {
        const val MIN_INTERVAL_MS = 250L
        const val AFTER_DELAY_MS = 220L
        const val MAX_SAMPLES = 12
        const val FALLBACK_TOLERANCE_MS = 500L
    }
}
