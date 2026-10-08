package com.benton.izukijs.ai

/**
 * 批量动作编排：顺序执行一组工具调用，默认遇错即停。
 *
 * 保持纯 Kotlin（不依赖 Android / org.json），便于单元测试；参数类型用泛型 [A]，
 * 生产环境传 [org.json.JSONObject]，测试可传简单类型。[isFailure] 由调用方注入，
 * 用于把工具返回文本判定为成功 / 失败。
 */
internal object BatchRunner {

    /** 单步动作：工具名 + 原始参数。 */
    data class Action<A>(val tool: String, val args: A)

    /** 单步结果。 */
    data class Step(val index: Int, val tool: String, val ok: Boolean, val result: String)

    /** 整体结果。[stoppedEarly] 表示因失败而提前中断，[truncated] 表示动作数超限被截断。 */
    data class Outcome(
        val ok: Boolean,
        val steps: List<Step>,
        val stoppedEarly: Boolean,
        val truncated: Boolean,
    )

    const val DEFAULT_MAX_ACTIONS = 20
    const val MAX_RESULT_CHARS = 2000

    fun <A> run(
        actions: List<Action<A>>,
        continueOnError: Boolean,
        maxActions: Int = DEFAULT_MAX_ACTIONS,
        execute: (String, A) -> String,
        isFailure: (String) -> Boolean,
    ): Outcome {
        val truncated = actions.size > maxActions
        val toRun = if (truncated) actions.take(maxActions) else actions
        val steps = ArrayList<Step>(toRun.size)
        var ok = true
        var stoppedEarly = false

        for ((index, action) in toRun.withIndex()) {
            val result = runCatching { execute(action.tool, action.args) }
                .getOrElse { "[错误] ${it.message}" }
            val failed = isFailure(result)
            steps.add(Step(index, action.tool, !failed, result.truncate(MAX_RESULT_CHARS)))
            if (failed) {
                ok = false
                if (!continueOnError) {
                    stoppedEarly = true
                    break
                }
            }
        }
        return Outcome(ok = ok, steps = steps, stoppedEarly = stoppedEarly, truncated = truncated)
    }

    private fun String.truncate(max: Int): String =
        if (length <= max) this else take(max) + "…（已截断 ${length - max} 字）"
}
