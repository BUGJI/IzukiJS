package com.benton.izukijs.developer

import androidx.annotation.StringRes
import com.benton.izukijs.R

/**
 * 单个整数型开发者参数的元数据：持久化 key、展示文案、默认值、范围与读写器。
 *
 * 仓库、校验和设置页共用同一份注册表，避免默认值 / 范围在多处漂移。
 */
data class DeveloperIntSpec(
    val key: String,
    @StringRes val labelRes: Int,
    @StringRes val descRes: Int,
    val default: Int,
    val range: IntRange,
    val get: (DeveloperSettings) -> Int,
    val set: (DeveloperSettings, Int) -> DeveloperSettings,
)

/** 设置页中的一个分组。 */
data class DeveloperSection(
    @StringRes val titleRes: Int,
    val specs: List<DeveloperIntSpec>,
    @StringRes val noteRes: Int? = null,
)

/**
 * 全部开发者参数的注册表。
 *
 * 新增参数时只需在这里补一条 spec，并加入 [sections]；仓库读写与页面渲染会自动覆盖。
 */
object DeveloperSettingSpecs {

    val animationProbeIntervalMs = DeveloperIntSpec(
        key = "animation_probe_interval_ms",
        labelRes = R.string.developer_animation_probe_interval,
        descRes = R.string.developer_animation_probe_interval_desc,
        default = 120,
        range = 30..1_000,
        get = { it.animationProbeIntervalMs },
        set = { s, v -> s.copy(animationProbeIntervalMs = v) },
    )

    val animationProbeCacheMs = DeveloperIntSpec(
        key = "animation_probe_cache_ms",
        labelRes = R.string.developer_animation_probe_cache,
        descRes = R.string.developer_animation_probe_cache_desc,
        default = 300,
        range = 0..5_000,
        get = { it.animationProbeCacheMs },
        set = { s, v -> s.copy(animationProbeCacheMs = v) },
    )

    val accessibilityTapDurationMs = DeveloperIntSpec(
        key = "accessibility_tap_duration_ms",
        labelRes = R.string.developer_accessibility_tap_duration,
        descRes = R.string.developer_accessibility_tap_duration_desc,
        default = 50,
        range = 10..500,
        get = { it.accessibilityTapDurationMs },
        set = { s, v -> s.copy(accessibilityTapDurationMs = v) },
    )

    val hidTapDurationMs = DeveloperIntSpec(
        key = "hid_tap_duration_ms",
        labelRes = R.string.developer_hid_tap_duration,
        descRes = R.string.developer_hid_tap_duration_desc,
        default = 50,
        range = 10..500,
        get = { it.hidTapDurationMs },
        set = { s, v -> s.copy(hidTapDurationMs = v) },
    )

    val inputTapMinMs = DeveloperIntSpec(
        key = "input_tap_min_ms",
        labelRes = R.string.developer_input_tap_min,
        descRes = R.string.developer_input_tap_min_desc,
        default = 70,
        range = 10..1_000,
        get = { it.inputTapMinMs },
        set = { s, v -> s.copy(inputTapMinMs = v) },
    )

    val inputTapMaxMs = DeveloperIntSpec(
        key = "input_tap_max_ms",
        labelRes = R.string.developer_input_tap_max,
        descRes = R.string.developer_input_tap_max_desc,
        default = 180,
        range = 100..3_000,
        get = { it.inputTapMaxMs },
        set = { s, v -> s.copy(inputTapMaxMs = v) },
    )

    val inputDefaultStepMs = DeveloperIntSpec(
        key = "input_default_step_ms",
        labelRes = R.string.developer_input_default_step,
        descRes = R.string.developer_input_default_step_desc,
        default = 16,
        range = 1..100,
        get = { it.inputDefaultStepMs },
        set = { s, v -> s.copy(inputDefaultStepMs = v) },
    )

    val captureWaitMs = DeveloperIntSpec(
        key = "capture_wait_ms",
        labelRes = R.string.developer_capture_wait,
        descRes = R.string.developer_capture_wait_desc,
        default = 1_000,
        range = 100..10_000,
        get = { it.captureWaitMs },
        set = { s, v -> s.copy(captureWaitMs = v) },
    )

    val firstCaptureRetry = DeveloperIntSpec(
        key = "first_capture_retry",
        labelRes = R.string.developer_first_capture_retry,
        descRes = R.string.developer_first_capture_retry_desc,
        default = 6,
        range = 1..30,
        get = { it.firstCaptureRetry },
        set = { s, v -> s.copy(firstCaptureRetry = v) },
    )

    val captureRetryDelayMs = DeveloperIntSpec(
        key = "capture_retry_delay_ms",
        labelRes = R.string.developer_capture_retry_delay,
        descRes = R.string.developer_capture_retry_delay_desc,
        default = 10,
        range = 0..200,
        get = { it.captureRetryDelayMs },
        set = { s, v -> s.copy(captureRetryDelayMs = v) },
    )

    val httpDefaultTimeoutMs = DeveloperIntSpec(
        key = "http_default_timeout_ms",
        labelRes = R.string.developer_http_default_timeout,
        descRes = R.string.developer_http_default_timeout_desc,
        default = 15_000,
        range = 1_000..60_000,
        get = { it.httpDefaultTimeoutMs },
        set = { s, v -> s.copy(httpDefaultTimeoutMs = v) },
    )

    val httpMaxBodyBytes = DeveloperIntSpec(
        key = "http_max_body_bytes",
        labelRes = R.string.developer_http_max_body,
        descRes = R.string.developer_http_max_body_desc,
        default = 2 * 1024 * 1024,
        range = 64 * 1024..32 * 1024 * 1024,
        get = { it.httpMaxBodyBytes },
        set = { s, v -> s.copy(httpMaxBodyBytes = v) },
    )

    val maxScriptBytes = DeveloperIntSpec(
        key = "max_script_bytes",
        labelRes = R.string.developer_max_script_bytes,
        descRes = R.string.developer_max_script_bytes_desc,
        default = 1024 * 1024,
        range = 64 * 1024..16 * 1024 * 1024,
        get = { it.maxScriptBytes },
        set = { s, v -> s.copy(maxScriptBytes = v) },
    )

    val maxRunErrorChars = DeveloperIntSpec(
        key = "max_run_error_chars",
        labelRes = R.string.developer_max_run_error_chars,
        descRes = R.string.developer_max_run_error_chars_desc,
        default = 4_000,
        range = 500..20_000,
        get = { it.maxRunErrorChars },
        set = { s, v -> s.copy(maxRunErrorChars = v) },
    )

    val mcpSessionTtlMinutes = DeveloperIntSpec(
        key = "mcp_session_ttl_minutes",
        labelRes = R.string.developer_mcp_session_ttl,
        descRes = R.string.developer_mcp_session_ttl_desc,
        default = 1_440,
        range = 1..10_080,
        get = { it.mcpSessionTtlMinutes },
        set = { s, v -> s.copy(mcpSessionTtlMinutes = v) },
    )

    val mcpMaxSessions = DeveloperIntSpec(
        key = "mcp_max_sessions",
        labelRes = R.string.developer_mcp_max_sessions,
        descRes = R.string.developer_mcp_max_sessions_desc,
        default = 256,
        range = 8..4_096,
        get = { it.mcpMaxSessions },
        set = { s, v -> s.copy(mcpMaxSessions = v) },
    )

    val mcpIdleTimeoutMs = DeveloperIntSpec(
        key = "mcp_idle_timeout_ms",
        labelRes = R.string.developer_mcp_idle_timeout,
        descRes = R.string.developer_mcp_idle_timeout_desc,
        default = 120_000,
        range = 5_000..600_000,
        get = { it.mcpIdleTimeoutMs },
        set = { s, v -> s.copy(mcpIdleTimeoutMs = v) },
    )

    val mcpMaxClientThreads = DeveloperIntSpec(
        key = "mcp_max_client_threads",
        labelRes = R.string.developer_mcp_max_threads,
        descRes = R.string.developer_mcp_max_threads_desc,
        default = 32,
        range = 2..256,
        get = { it.mcpMaxClientThreads },
        set = { s, v -> s.copy(mcpMaxClientThreads = v) },
    )

    val floatingWindowRefreshMs = DeveloperIntSpec(
        key = "floating_window_refresh_ms",
        labelRes = R.string.developer_floating_refresh,
        descRes = R.string.developer_floating_refresh_desc,
        default = 250,
        range = 50..2_000,
        get = { it.floatingWindowRefreshMs },
        set = { s, v -> s.copy(floatingWindowRefreshMs = v) },
    )

    val floatingWindowMaxLogLines = DeveloperIntSpec(
        key = "floating_window_max_log_lines",
        labelRes = R.string.developer_floating_max_lines,
        descRes = R.string.developer_floating_max_lines_desc,
        default = 200,
        range = 20..2_000,
        get = { it.floatingWindowMaxLogLines },
        set = { s, v -> s.copy(floatingWindowMaxLogLines = v) },
    )

    val maxImageMatches = DeveloperIntSpec(
        key = "max_image_matches",
        labelRes = R.string.developer_max_image_matches,
        descRes = R.string.developer_max_image_matches_desc,
        default = 200,
        range = 10..2_000,
        get = { it.maxImageMatches },
        set = { s, v -> s.copy(maxImageMatches = v) },
    )

    val defaultMaxRegions = DeveloperIntSpec(
        key = "default_max_regions",
        labelRes = R.string.developer_default_max_regions,
        descRes = R.string.developer_default_max_regions_desc,
        default = 20,
        range = 1..200,
        get = { it.defaultMaxRegions },
        set = { s, v -> s.copy(defaultMaxRegions = v) },
    )

    val agentTreeProbeBudgetMs = DeveloperIntSpec(
        key = "agent_tree_probe_budget_ms",
        labelRes = R.string.developer_agent_tree_probe,
        descRes = R.string.developer_agent_tree_probe_desc,
        default = 1_500,
        range = 200..10_000,
        get = { it.agentTreeProbeBudgetMs },
        set = { s, v -> s.copy(agentTreeProbeBudgetMs = v) },
    )

    val agentHitProbeBudgetMs = DeveloperIntSpec(
        key = "agent_hit_probe_budget_ms",
        labelRes = R.string.developer_agent_hit_probe,
        descRes = R.string.developer_agent_hit_probe_desc,
        default = 1_200,
        range = 200..10_000,
        get = { it.agentHitProbeBudgetMs },
        set = { s, v -> s.copy(agentHitProbeBudgetMs = v) },
    )

    val agentMaxDumpChars = DeveloperIntSpec(
        key = "agent_max_dump_chars",
        labelRes = R.string.developer_agent_max_dump_chars,
        descRes = R.string.developer_agent_max_dump_chars_desc,
        default = 4_000,
        range = 500..50_000,
        get = { it.agentMaxDumpChars },
        set = { s, v -> s.copy(agentMaxDumpChars = v) },
    )

    val agentDefaultWaitTimeoutMs = DeveloperIntSpec(
        key = "agent_default_wait_timeout_ms",
        labelRes = R.string.developer_agent_default_wait_timeout,
        descRes = R.string.developer_agent_default_wait_timeout_desc,
        default = 10_000,
        range = 1_000..120_000,
        get = { it.agentDefaultWaitTimeoutMs },
        set = { s, v -> s.copy(agentDefaultWaitTimeoutMs = v) },
    )

    val agentDefaultWaitIntervalMs = DeveloperIntSpec(
        key = "agent_default_wait_interval_ms",
        labelRes = R.string.developer_agent_default_wait_interval,
        descRes = R.string.developer_agent_default_wait_interval_desc,
        default = 400,
        range = 50..5_000,
        get = { it.agentDefaultWaitIntervalMs },
        set = { s, v -> s.copy(agentDefaultWaitIntervalMs = v) },
    )

    val agentSettleDefaultMs = DeveloperIntSpec(
        key = "agent_settle_default_ms",
        labelRes = R.string.developer_agent_settle_default,
        descRes = R.string.developer_agent_settle_default_desc,
        default = 600,
        range = 0..10_000,
        get = { it.agentSettleDefaultMs },
        set = { s, v -> s.copy(agentSettleDefaultMs = v) },
    )

    val agentSettlePollMs = DeveloperIntSpec(
        key = "agent_settle_poll_ms",
        labelRes = R.string.developer_agent_settle_poll,
        descRes = R.string.developer_agent_settle_poll_desc,
        default = 150,
        range = 20..2_000,
        get = { it.agentSettlePollMs },
        set = { s, v -> s.copy(agentSettlePollMs = v) },
    )

    val agentSettleMaxMs = DeveloperIntSpec(
        key = "agent_settle_max_ms",
        labelRes = R.string.developer_agent_settle_max,
        descRes = R.string.developer_agent_settle_max_desc,
        default = 4_000,
        range = 100..30_000,
        get = { it.agentSettleMaxMs },
        set = { s, v -> s.copy(agentSettleMaxMs = v) },
    )

    val visionTemplateCacheEntries = DeveloperIntSpec(
        key = "vision_template_cache_entries",
        labelRes = R.string.developer_vision_template_cache,
        descRes = R.string.developer_vision_template_cache_desc,
        default = 8,
        range = 1..64,
        get = { it.visionTemplateCacheEntries },
        set = { s, v -> s.copy(visionTemplateCacheEntries = v) },
    )

    val visionNeuralSessions = DeveloperIntSpec(
        key = "vision_neural_sessions",
        labelRes = R.string.developer_vision_neural_sessions,
        descRes = R.string.developer_vision_neural_sessions_desc,
        default = 3,
        range = 1..16,
        get = { it.visionNeuralSessions },
        set = { s, v -> s.copy(visionNeuralSessions = v) },
    )

    val visionNeuralThreads = DeveloperIntSpec(
        key = "vision_neural_threads",
        labelRes = R.string.developer_vision_neural_threads,
        descRes = R.string.developer_vision_neural_threads_desc,
        default = 2,
        range = 1..8,
        get = { it.visionNeuralThreads },
        set = { s, v -> s.copy(visionNeuralThreads = v) },
    )

    val pipelineDefaultMaxSteps = DeveloperIntSpec(
        key = "pipeline_default_max_steps",
        labelRes = R.string.developer_pipeline_default_max_steps,
        descRes = R.string.developer_pipeline_default_max_steps_desc,
        default = 10_000,
        range = 100..1_000_000,
        get = { it.pipelineDefaultMaxSteps },
        set = { s, v -> s.copy(pipelineDefaultMaxSteps = v) },
    )

    val pipelineDefaultTimeoutSec = DeveloperIntSpec(
        key = "pipeline_default_timeout_sec",
        labelRes = R.string.developer_pipeline_default_timeout,
        descRes = R.string.developer_pipeline_default_timeout_desc,
        default = 0,
        range = 0..86_400,
        get = { it.pipelineDefaultTimeoutSec },
        set = { s, v -> s.copy(pipelineDefaultTimeoutSec = v) },
    )

    val pipelineMaxRecognitionDepth = DeveloperIntSpec(
        key = "pipeline_max_recognition_depth",
        labelRes = R.string.developer_pipeline_max_depth,
        descRes = R.string.developer_pipeline_max_depth_desc,
        default = 8,
        range = 1..32,
        get = { it.pipelineMaxRecognitionDepth },
        set = { s, v -> s.copy(pipelineMaxRecognitionDepth = v) },
    )

    val recordingMaxUniqueFrames = DeveloperIntSpec(
        key = "recording_max_unique_frames",
        labelRes = R.string.developer_recording_max_frames,
        descRes = R.string.developer_recording_max_frames_desc,
        default = 800,
        range = 50..5_000,
        get = { it.recordingMaxUniqueFrames },
        set = { s, v -> s.copy(recordingMaxUniqueFrames = v) },
    )

    val recordingFrameQuality = DeveloperIntSpec(
        key = "recording_frame_quality",
        labelRes = R.string.developer_recording_frame_quality,
        descRes = R.string.developer_recording_frame_quality_desc,
        default = 88,
        range = 50..100,
        get = { it.recordingFrameQuality },
        set = { s, v -> s.copy(recordingFrameQuality = v) },
    )

    val recordingFrameMaxDimension = DeveloperIntSpec(
        key = "recording_frame_max_dimension",
        labelRes = R.string.developer_recording_frame_max_dimension,
        descRes = R.string.developer_recording_frame_max_dimension_desc,
        default = 0,
        range = 0..4_320,
        get = { it.recordingFrameMaxDimension },
        set = { s, v -> s.copy(recordingFrameMaxDimension = v) },
    )

    val sections: List<DeveloperSection> = listOf(
        DeveloperSection(
            titleRes = R.string.developer_section_control,
            specs = listOf(
                animationProbeIntervalMs,
                animationProbeCacheMs,
                accessibilityTapDurationMs,
                hidTapDurationMs,
                inputTapMinMs,
                inputTapMaxMs,
                inputDefaultStepMs,
            ),
        ),
        DeveloperSection(
            titleRes = R.string.developer_section_capture,
            specs = listOf(
                captureWaitMs,
                firstCaptureRetry,
                captureRetryDelayMs,
            ),
        ),
        DeveloperSection(
            titleRes = R.string.developer_section_network,
            noteRes = R.string.developer_section_network_note,
            specs = listOf(
                httpDefaultTimeoutMs,
                httpMaxBodyBytes,
                maxScriptBytes,
                maxRunErrorChars,
                mcpSessionTtlMinutes,
                mcpMaxSessions,
                mcpIdleTimeoutMs,
                mcpMaxClientThreads,
            ),
        ),
        DeveloperSection(
            titleRes = R.string.developer_section_runtime,
            specs = listOf(
                floatingWindowRefreshMs,
                floatingWindowMaxLogLines,
            ),
        ),
        DeveloperSection(
            titleRes = R.string.developer_section_ai,
            specs = listOf(
                maxImageMatches,
                defaultMaxRegions,
                agentTreeProbeBudgetMs,
                agentHitProbeBudgetMs,
                agentMaxDumpChars,
                agentDefaultWaitTimeoutMs,
                agentDefaultWaitIntervalMs,
                agentSettleDefaultMs,
                agentSettlePollMs,
                agentSettleMaxMs,
                visionTemplateCacheEntries,
                visionNeuralSessions,
                visionNeuralThreads,
            ),
        ),
        DeveloperSection(
            titleRes = R.string.developer_section_pipeline,
            specs = listOf(
                pipelineDefaultMaxSteps,
                pipelineDefaultTimeoutSec,
                pipelineMaxRecognitionDepth,
            ),
        ),
        DeveloperSection(
            titleRes = R.string.developer_section_recording,
            specs = listOf(
                recordingMaxUniqueFrames,
                recordingFrameQuality,
                recordingFrameMaxDimension,
            ),
        ),
    )

    val all: List<DeveloperIntSpec> = sections.flatMap { it.specs }
}
