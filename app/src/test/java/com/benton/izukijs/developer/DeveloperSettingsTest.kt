package com.benton.izukijs.developer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperSettingsTest {

    @Test
    fun defaultsMatchCurrentHardcodedValues() {
        val settings = DeveloperSettings()

        assertEquals(120, settings.animationProbeIntervalMs)
        assertEquals(300, settings.animationProbeCacheMs)
        assertEquals(50, settings.accessibilityTapDurationMs)
        assertEquals(50, settings.hidTapDurationMs)
        assertEquals(70, settings.inputTapMinMs)
        assertEquals(180, settings.inputTapMaxMs)
        assertEquals(16, settings.inputDefaultStepMs)
        assertEquals(1_000, settings.captureWaitMs)
        assertEquals(6, settings.firstCaptureRetry)
        assertEquals(10, settings.captureRetryDelayMs)
        assertEquals(15_000, settings.httpDefaultTimeoutMs)
        assertEquals(2 * 1024 * 1024, settings.httpMaxBodyBytes)
        assertEquals(1024 * 1024, settings.maxScriptBytes)
        assertEquals(4_000, settings.maxRunErrorChars)
        assertEquals(1_440, settings.mcpSessionTtlMinutes)
        assertEquals(256, settings.mcpMaxSessions)
        assertEquals(120_000, settings.mcpIdleTimeoutMs)
        assertEquals(32, settings.mcpMaxClientThreads)
        assertEquals(250, settings.floatingWindowRefreshMs)
        assertEquals(200, settings.floatingWindowMaxLogLines)
        assertEquals(200, settings.maxImageMatches)
        assertEquals(20, settings.defaultMaxRegions)
        assertEquals(1_500, settings.agentTreeProbeBudgetMs)
        assertEquals(1_200, settings.agentHitProbeBudgetMs)
        assertEquals(4_000, settings.agentMaxDumpChars)
        assertEquals(10_000, settings.agentDefaultWaitTimeoutMs)
        assertEquals(400, settings.agentDefaultWaitIntervalMs)
        assertEquals(600, settings.agentSettleDefaultMs)
        assertEquals(150, settings.agentSettlePollMs)
        assertEquals(4_000, settings.agentSettleMaxMs)
        assertEquals(8, settings.visionTemplateCacheEntries)
        assertEquals(3, settings.visionNeuralSessions)
        assertEquals(2, settings.visionNeuralThreads)
        assertEquals(10_000, settings.pipelineDefaultMaxSteps)
        assertEquals(0, settings.pipelineDefaultTimeoutSec)
        assertEquals(8, settings.pipelineMaxRecognitionDepth)
        assertEquals(1_600, settings.recordingMaxUniqueFrames)
        assertEquals(82, settings.recordingFrameQuality)
        assertEquals(960, settings.recordingFrameMaxDimension)
    }

    @Test
    fun normalizationClampsEverySpecIntoRange() {
        val settings = DeveloperSettings(
            animationProbeIntervalMs = -1,
            animationProbeCacheMs = Int.MAX_VALUE,
            accessibilityTapDurationMs = 0,
            hidTapDurationMs = 999_999,
            captureWaitMs = 1,
            firstCaptureRetry = 0,
            httpDefaultTimeoutMs = 1,
            mcpMaxSessions = 1,
            maxImageMatches = 1,
            agentDefaultWaitTimeoutMs = 1,
            agentSettlePollMs = 1,
        ).normalized()

        DeveloperSettingSpecs.all.forEach { spec ->
            assertTrue(
                "${spec.key} should be within ${spec.range}",
                spec.get(settings) in spec.range,
            )
        }
    }

    @Test
    fun normalizationKeepsTapMinBelowTapMax() {
        val settings = DeveloperSettings(inputTapMinMs = 500, inputTapMaxMs = 100).normalized()

        assertTrue(settings.inputTapMinMs < settings.inputTapMaxMs)
    }

    @Test
    fun normalizationKeepsSettleDefaultWithinSettleMax() {
        val settings = DeveloperSettings(
            agentSettleDefaultMs = 9_000,
            agentSettleMaxMs = 1_000,
        ).normalized()

        assertTrue(settings.agentSettleDefaultMs <= settings.agentSettleMaxMs)
    }
}
