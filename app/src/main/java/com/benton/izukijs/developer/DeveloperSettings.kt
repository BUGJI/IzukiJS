package com.benton.izukijs.developer

/**
 * 开发者选项参数。
 *
 * 这些值原本以 `const val` 散落在各模块中，集中到这里后由 [DeveloperSettingSpecs]
 * 统一提供默认值、范围与持久化 key。字段顺序与设置页分组保持一致。
 *
 * [unlocked] 只控制设置入口是否可见，不参与参数校验；其余字段由 [normalized] 统一裁剪。
 */
data class DeveloperSettings(
    val unlocked: Boolean = false,

    // ---- 控件树与输入 ----
    val animationProbeIntervalMs: Int = 120,
    val animationProbeCacheMs: Int = 300,
    val accessibilityTapDurationMs: Int = 50,
    val hidTapDurationMs: Int = 50,
    val inputTapMinMs: Int = 70,
    val inputTapMaxMs: Int = 180,
    val inputDefaultStepMs: Int = 16,

    // ---- 截图 ----
    val captureWaitMs: Int = 1_000,
    val firstCaptureRetry: Int = 6,
    val captureRetryDelayMs: Int = 10,

    // ---- 网络与 MCP ----
    val httpDefaultTimeoutMs: Int = 15_000,
    val httpMaxBodyBytes: Int = 2 * 1024 * 1024,
    val maxScriptBytes: Int = 1024 * 1024,
    val maxRunErrorChars: Int = 4_000,
    val mcpSessionTtlMinutes: Int = 1_440,
    val mcpMaxSessions: Int = 256,
    val mcpIdleTimeoutMs: Int = 120_000,
    val mcpMaxClientThreads: Int = 32,

    // ---- 运行时与日志 ----
    val floatingWindowRefreshMs: Int = 250,
    val floatingWindowMaxLogLines: Int = 200,

    // ---- 视觉与 AI ----
    val maxImageMatches: Int = 200,
    val defaultMaxRegions: Int = 20,
    val agentTreeProbeBudgetMs: Int = 1_500,
    val agentHitProbeBudgetMs: Int = 1_200,
    val agentMaxDumpChars: Int = 4_000,
    val agentDefaultWaitTimeoutMs: Int = 10_000,
    val agentDefaultWaitIntervalMs: Int = 400,
    val agentSettleDefaultMs: Int = 600,
    val agentSettlePollMs: Int = 150,
    val agentSettleMaxMs: Int = 4_000,

    // ---- 统一视觉缓存 ----
    val visionTemplateCacheEntries: Int = 8,
    val visionNeuralSessions: Int = 3,
    val visionNeuralThreads: Int = 2,

    // ---- 流水线 ----
    val pipelineDefaultMaxSteps: Int = 10_000,
    val pipelineDefaultTimeoutSec: Int = 0,
    val pipelineMaxRecognitionDepth: Int = 8,

    // ---- 录制 ----
    val recordingMaxUniqueFrames: Int = 800,
    val recordingFrameQuality: Int = 88,
    val recordingFrameMaxDimension: Int = 0,
) {

    /** 按注册表逐项裁剪到合法范围，并修正跨字段约束。 */
    fun normalized(): DeveloperSettings {
        var result = this
        DeveloperSettingSpecs.all.forEach { spec ->
            result = spec.set(result, spec.get(result).coerceIn(spec.range))
        }

        if (result.inputTapMinMs >= result.inputTapMaxMs) {
            result = result.copy(
                inputTapMaxMs = (result.inputTapMinMs + 1)
                    .coerceIn(DeveloperSettingSpecs.inputTapMaxMs.range),
            )
            if (result.inputTapMinMs >= result.inputTapMaxMs) {
                result = result.copy(
                    inputTapMinMs = (result.inputTapMaxMs - 1)
                        .coerceIn(DeveloperSettingSpecs.inputTapMinMs.range),
                )
            }
        }

        if (result.agentSettleDefaultMs > result.agentSettleMaxMs) {
            result = result.copy(agentSettleDefaultMs = result.agentSettleMaxMs)
        }

        return result
    }
}
