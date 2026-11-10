package com.benton.izukijs.flow

import kotlin.random.Random

/** 编排执行中与 Android 无关的纯逻辑，便于单元测试。 */
object FlowPlanner {

    /** 按配置顺序返回本次要执行的步骤；随机模式只打乱副本。 */
    fun orderedSteps(
        steps: List<FlowStep>,
        order: FlowOrder,
        random: Random = Random.Default,
    ): List<FlowStep> = when (order) {
        FlowOrder.SEQUENTIAL -> steps
        FlowOrder.RANDOM -> steps.shuffled(random)
    }

    /** 当前步骤失败后是否继续执行后续步骤。 */
    fun shouldContinue(continueOnError: Boolean, success: Boolean): Boolean =
        success || continueOnError
}
