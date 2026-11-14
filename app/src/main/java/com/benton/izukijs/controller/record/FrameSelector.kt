package com.benton.izukijs.controller.record

/** 一次成功写盘的采样帧；[timestamp] 为采集开始时间。 */
data class FrameSample(val timestamp: Long, val path: String)

/**
 * 记录最近若干采样帧，并负责给动作挑选“动作前帧”。
 *
 * 与单纯的最近帧队列不同，这里有两个边界：
 * - 页面切换后由 [invalidate] 清空，避免把上一个页面的画面挂到新页面的动作上；
 * - 只接受 [maxAgeMs] 以内的样本，帧数上限后采样停止时不会继续返回很久以前的帧。
 */
class FrameSelector(
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) {
    private val samples = ArrayDeque<FrameSample>()

    @Synchronized
    fun add(timestamp: Long, path: String): FrameSample {
        val sample = FrameSample(timestamp, path)
        samples.addLast(sample)
        while (samples.size > maxSamples) samples.removeFirst()
        return sample
    }

    /** 动作时间点之前最近且未过期的帧。 */
    @Synchronized
    fun before(timestamp: Long): FrameSample? {
        val sample = samples.lastOrNull { it.timestamp <= timestamp } ?: return null
        return sample.takeIf { timestamp - it.timestamp <= maxAgeMs }
    }

    /** 指定时间点之后最早的帧；用于动作后补帧时复用采样线程已抓到的画面。 */
    @Synchronized
    fun atOrAfter(timestamp: Long): FrameSample? =
        samples.firstOrNull { it.timestamp >= timestamp }

    @Synchronized
    fun invalidate() {
        samples.clear()
    }

    @Synchronized
    fun clear() {
        samples.clear()
    }

    @Synchronized
    fun size(): Int = samples.size

    companion object {
        const val DEFAULT_MAX_SAMPLES = 12

        /** 超过采样间隔两倍仍没有新样本时，宁可判为“无动作前帧”，也不返回旧页面。 */
        const val DEFAULT_MAX_AGE_MS = 2_000L
    }
}
