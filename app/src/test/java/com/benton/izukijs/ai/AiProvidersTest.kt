package com.benton.izukijs.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProvidersTest {

    @Test
    fun matchIgnoresTrailingSlashAndWhitespace() {
        assertEquals("deepseek", AiProviders.match("  https://api.deepseek.com/v1/  ")?.id)
    }

    @Test
    fun matchReturnsNullForCustomUrl() {
        assertNull(AiProviders.match("https://gateway.example.com/v1"))
    }

    @Test
    fun matchReturnsNullForBlankUrl() {
        assertNull(AiProviders.match(""))
    }

    @Test
    fun everyPresetHasDistinctIdAndConfiguredModel() {
        val ids = AiProviders.presets.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        AiProviders.presets.forEach { preset ->
            assertTrue(preset.baseUrl.startsWith("http"))
            assertTrue(preset.model.isNotBlank())
        }
    }

    @Test
    fun canTestDoesNotRequireEnabled() {
        val config = AiConfig(
            enabled = false,
            baseUrl = "https://api.example.com/v1",
            apiKey = "k",
            model = "m",
        )
        assertTrue(config.canTest)
        assertFalse(config.isConfigured)
    }

    @Test
    fun canTestRequiresEveryConnectionField() {
        assertFalse(AiConfig(apiKey = "k", model = "m", baseUrl = "").canTest)
        assertFalse(AiConfig(apiKey = "", model = "m").canTest)
        assertFalse(AiConfig(apiKey = "k", model = " ").canTest)
    }
}
