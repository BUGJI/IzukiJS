package com.benton.izukijs.controller

import java.util.concurrent.atomic.AtomicLong

/**
 * `uiautomator dump` 的按设备自适应预算。
 *
 * 不同机型 / ROM 的 dump 耗时差异可达 2~3 倍（实测 0.4s ~ 3.5s），单一固定预算必然在部分
 * 设备上持续超时。这里记录本进程内观测到的成功耗时，并把 `实测 × 1.6` 作为后续读取的建议
 * 预算；首次尚未观测到时，由调用方在超时后按 [retryBudgetMs] 放大重试一次。
 *
 * 只保存进程内数据：重启后的第一次读取仍会走「超时 → 放大重试 → 记录」的一次性学习成本，
 * 避免为这个局部数据引入额外的持久化与备份语义。
 */
object UiautomatorDumpBudget {

    private val baseline = AtomicLong(0L)

    /** 最近观测到的典型 dump 耗时；未观测到为 0。 */
    fun measuredMs(): Long = baseline.get()

    /** 仅供测试：清空进程内学习到的基线，避免用例之间互相污染。 */
    internal fun resetForTest() {
        baseline.set(0L)
    }

    /** 由实测耗时换算的建议预算；未观测到返回 0，供调用方与设置值取 max。 */
    fun effectiveBaselineMs(): Long = effectiveBudgetFor(baseline.get())

    /** 记录一次成功 dump 的实际耗时，按 25% 权重平滑，避免单次抖动把预算拉满。 */
    fun recordMeasured(measuredMs: Long) {
        if (measuredMs <= 0L) return
        while (true) {
            val current = baseline.get()
            val next = nextBaselineFor(current, measuredMs)
            if (baseline.compareAndSet(current, next)) return
        }
    }

    /**
     * 首次失败后的放大预算；返回 null 表示不应重试。
     *
     * 已经学到本机基线且预算达到 [NO_RETRY_BUDGET_MS] 时不再放大：此时超时更可能是界面
     * 持续动画或锁屏，继续等待只会把设备队列堵在空转上。
     */
    fun retryBudgetMs(attemptedMs: Long): Long? = retryBudgetFor(attemptedMs, baseline.get())

    internal fun effectiveBudgetFor(baselineMs: Long): Long {
        if (baselineMs <= 0L) return 0L
        return (baselineMs * HEADROOM_NUMERATOR / HEADROOM_DENOMINATOR)
            .coerceIn(MIN_EFFECTIVE_MS, MAX_BUDGET_MS)
    }

    internal fun nextBaselineFor(currentMs: Long, measuredMs: Long): Long {
        if (measuredMs <= 0L) return currentMs
        if (currentMs <= 0L) return measuredMs
        return (currentMs * (SMOOTHING_DIVISOR - 1) + measuredMs) / SMOOTHING_DIVISOR
    }

    internal fun retryBudgetFor(attemptedMs: Long, baselineMs: Long): Long? {
        if (attemptedMs <= 0L) return null
        val current = maxOf(attemptedMs, effectiveBudgetFor(baselineMs))
        if (current >= NO_RETRY_BUDGET_MS) return null
        return (current * RETRY_NUMERATOR / RETRY_DENOMINATOR).coerceAtMost(MAX_BUDGET_MS)
    }

    private const val HEADROOM_NUMERATOR = 16L
    private const val HEADROOM_DENOMINATOR = 10L
    private const val RETRY_NUMERATOR = 5L
    private const val RETRY_DENOMINATOR = 2L
    private const val SMOOTHING_DIVISOR = 4L
    private const val MIN_EFFECTIVE_MS = 1_500L
    private const val MAX_BUDGET_MS = 10_000L
    private const val NO_RETRY_BUDGET_MS = 3_000L
}
