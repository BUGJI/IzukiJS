package com.benton.izukijs.controller.record

import android.graphics.Bitmap
import android.os.SystemClock
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

/**
 * 用户录制期间的截图采样器。
 *
 * 以固定间隔抓帧并保存到 [RecordingWriter]，同时维护最近若干帧的时间戳索引。
 * 用户动作发生时用 [frameBefore] 取动作前最近一帧，作为 `beforeFrame` 写入事件。
 *
 * 页面切换后调用 [invalidateSamples]：旧页面的样本不再参与 `beforeFrame`，
 * 避免把新页面的动作标成上一个页面的截图。
 */
class RecordingCaptureLoop(
    private val writer: RecordingWriter,
    private val intervalMs: Long,
    private val logBus: LogBus,
    /** [fresh] 为 true 时要求后端等待一帧比调用时刻更新的画面，避免拿到缓存的旧页。 */
    private val captureProvider: (fresh: Boolean) -> Bitmap?,
    private val onCaptureFailure: () -> Unit = {},
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val samples = FrameSelector(
        maxAgeMs = maxOf(intervalMs * 2, FrameSelector.DEFAULT_MAX_AGE_MS),
    )
    private val afterLock = Any()
    private val afterJobs = mutableSetOf<Job>()
    private var job: Job? = null

    @Volatile
    private var stopped = false

    @Volatile
    private var acceptingAfter = true

    /** 连续失败起点；按持续时间而不是次数判断，避免旋转稳定窗口误报截图失败。 */
    @Volatile
    private var failureSinceUptime = 0L

    @Volatile
    private var failureReported = false

    fun start() {
        if (job != null) return
        stopped = false
        acceptingAfter = true
        job = scope.launch {
            // 先抓一帧，保证第一下点击也有可用的“动作前”画面。
            captureOnce(fresh = false)
            while (isActive && !stopped) {
                delay(intervalMs.coerceAtLeast(MIN_INTERVAL_MS))
                captureOnce(fresh = false)
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
        samples.clear()
    }

    /**
     * 返回动作时间点之前最近的一帧。
     *
     * 采样队列只保留最近若干帧，且页面切换后会被清空；找不到动作前的帧时返回 null，
     * 不再用动作之后或上一个页面的画面冒充 beforeFrame。
     */
    fun frameBefore(timestamp: Long): String? = samples.before(timestamp)?.path

    /** 页面切换后调用：丢弃旧页面样本，并要求下一帧强制写盘。 */
    fun invalidateSamples() {
        samples.invalidate()
        writer.requestFreshFrame()
    }

    /** 动作后补一帧，捕捉页面跳转后的画面；[actionSeq] 用于把帧回填到动作。 */
    fun scheduleAfter(timestamp: Long, actionSeq: Long? = null) {
        if (stopped || !acceptingAfter) return
        val job = scope.launch {
            delay(AFTER_DELAY_MS)
            // 如果采样线程已经抓到了足够晚的帧，直接复用，避免重复截图。
            val existing = samples.atOrAfter(timestamp + AFTER_DELAY_MS)
            val sample = existing ?: captureOnce(fresh = true) ?: return@launch
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

    private fun captureOnce(fresh: Boolean): FrameSample? {
        if (stopped) return null
        val startedWall = System.currentTimeMillis()
        // 到帧上限后继续写 stale screencap 事件，让时间线保持连续；但不再把旧帧
        // 计入样本索引，否则后续动作会被标成上限之前的页面。
        if (writer.frameCapReached()) {
            // 上限之后没有可信的新样本：清掉旧索引，避免后续动作继续挂上限前的页面。
            samples.invalidate()
            writer.recordCappedFrame(
                atMs = writer.timestampOf(startedWall),
                source = RecordSource.USER,
            )
            return null
        }
        val bitmap = runCatching {
            OverlayCoordinator.withoutOverlay { captureProvider(fresh) }
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
                failureSinceUptime = 0L
                failureReported = false
                return samples.add(timestamp, path)
            }
            return null
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    private fun noteCaptureFailure(message: String?) {
        if (message != null) logBus.debug("用户录制截图失败: $message")
        val now = SystemClock.uptimeMillis()
        if (failureSinceUptime == 0L) failureSinceUptime = now
        // 旋转稳定窗口内 capture() 会短暂返回空帧（由其它后端兜底），不应按连续次数误报；
        // 只有持续拿不到画面超过宽限期才提示截图失败。
        if (!failureReported && now - failureSinceUptime >= FAILURE_REPORT_GRACE_MS) {
            failureReported = true
            onCaptureFailure()
        }
    }

    private companion object {
        const val MIN_INTERVAL_MS = 250L
        const val AFTER_DELAY_MS = 220L
        const val STOP_GRACE_MS = 1_000L
        const val FAILURE_REPORT_GRACE_MS = 5_000L
    }
}
