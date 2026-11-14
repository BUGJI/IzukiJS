package com.benton.izukijs.core.perf

import java.util.concurrent.atomic.AtomicLong

/**
 * 单个性能指标的累计器：计数、总耗时、最大值与固定容量环形样本。
 *
 * 独立成纯类后可以在 JVM 上验证环形缓冲与分位数，不依赖 `PerfMetrics` 的单例状态。
 */
internal class MetricAccumulator(private val sampleCapacity: Int = DEFAULT_CAPACITY) {

    init {
        require(sampleCapacity > 0) { "sampleCapacity must be positive" }
    }

    private val count = AtomicLong(0)
    private val totalNanos = AtomicLong(0)
    private val maxNanos = AtomicLong(0)
    private val samples = LongArray(sampleCapacity)
    private var sampleCount = 0
    private var nextSample = 0

    fun add(value: Long) {
        val sample = value.coerceAtLeast(0L)
        count.incrementAndGet()
        totalNanos.addAndGet(sample)
        maxNanos.updateAndGet { current -> if (sample > current) sample else current }
        synchronized(samples) {
            samples[nextSample] = sample
            nextSample = (nextSample + 1) % sampleCapacity
            if (sampleCount < sampleCapacity) sampleCount++
        }
    }

    fun count(): Long = count.get()

    fun totalNanos(): Long = totalNanos.get()

    fun maxNanos(): Long = maxNanos.get()

    fun snapshot(): LongArray = synchronized(samples) {
        LongArray(sampleCount) { samples[it] }
    }

    internal companion object {
        const val DEFAULT_CAPACITY = 4_096
    }
}

internal object PerfStats {

    fun percentile(sorted: LongArray, fraction: Double): Long {
        if (sorted.isEmpty()) return 0L
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}
