package com.benton.izukijs.ai

import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.controller.UiautomatorDumpBudget

/**
 * 控件树读取的预算放大重试逻辑。
 *
 * 慢设备首次读取时还没有本机基线，固定预算必然超时；超时后按
 * [UiautomatorDumpBudget.retryBudgetMs] 放大一次，成功后会记录真实耗时，
 * 后续读取直接使用更大的预算。这里不依赖 Android，便于用假 provider 做确定性测试。
 */
internal object TreeProbe {

    /** 读取一次；仅在「超时」时放大预算重试一次。 */
    fun probe(
        initialBudgetMs: Long,
        provider: (Long) -> NodeTreeResult,
    ): NodeTreeResult {
        val first = provider(initialBudgetMs)
        val failure = first as? NodeTreeResult.Failure ?: return first
        if (!failure.timedOut) return failure
        val retryBudget = UiautomatorDumpBudget.retryBudgetMs(failure.budgetMs) ?: return failure
        return retryIfTimedOut(failure, retryBudget, provider)
    }

    /** 在 [deadline] 内取一次控件树；预算不足时返回 null（调用方应直接放弃本轮）。 */
    fun treeProbe(
        deadline: Long,
        now: () -> Long = System::currentTimeMillis,
        provider: (Long) -> NodeTreeResult,
    ): NodeTreeResult? {
        val remaining = deadline - now()
        if (remaining < MIN_PROBE_MS) return null
        val first = provider(minOf(remaining, TREE_PROBE_BUDGET_MS))
        val failure = first as? NodeTreeResult.Failure ?: return first
        val retryBudget = UiautomatorDumpBudget.retryBudgetMs(failure.budgetMs) ?: return first
        // 重试也必须落在本轮 deadline 内，避免等待类工具被一次慢 dump 大幅超支。
        if (retryBudget > deadline - now()) return first
        return retryIfTimedOut(failure, retryBudget, provider)
    }

    private fun retryIfTimedOut(
        failure: NodeTreeResult.Failure,
        retryBudgetMs: Long,
        probe: (Long) -> NodeTreeResult,
    ): NodeTreeResult {
        if (!failure.timedOut) return failure
        val second = probe(retryBudgetMs)
        if (second !is NodeTreeResult.Failure) return second
        return second.copy(
            message = "首次 ${failure.budgetMs}ms 内未返回，放大到 ${retryBudgetMs}ms 重试仍失败：${second.message}",
        )
    }
}
