package com.benton.izukijs.mcp.protocol

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpArgumentSummarizerTest {

    @Test
    fun emptyArgumentsProduceEmptyString() {
        assertEquals("", McpArgumentSummarizer.summarize(JSONObject()))
    }

    @Test
    fun sensitiveKeysAreMasked() {
        val summary = JSONObject(
            McpArgumentSummarizer.summarize(
                JSONObject()
                    .put("token", "abc")
                    .put("api_key", "k")
                    .put("Authorization", "Bearer x"),
            ),
        )

        assertEquals("***", summary.getString("token"))
        assertEquals("***", summary.getString("api_key"))
        assertEquals("***", summary.getString("Authorization"))
    }

    @Test
    fun contentStringsUseLengthPlaceholder() {
        val summary = JSONObject(
            McpArgumentSummarizer.summarize(JSONObject().put("content", "a".repeat(500))),
        )

        assertEquals("<500 chars>", summary.getString("content"))
    }

    @Test
    fun longStringsAreTruncated() {
        val summary = JSONObject(
            McpArgumentSummarizer.summarize(JSONObject().put("text", "a".repeat(100))),
        )

        assertEquals(80, summary.getString("text").length)
    }

    @Test
    fun nestedObjectsAndArraysUseShapePlaceholders() {
        val summary = JSONObject(
            McpArgumentSummarizer.summarize(
                JSONObject()
                    .put("object", JSONObject().put("a", 1))
                    .put("array", JSONArray().put(1).put(2).put(3)),
            ),
        )

        assertEquals("{…}", summary.getString("object"))
        assertEquals("[3]", summary.getString("array"))
    }

    @Test
    fun scalarsArePreserved() {
        val summary = JSONObject(
            McpArgumentSummarizer.summarize(
                JSONObject().put("count", 3).put("enabled", true).put("ratio", 1.5),
            ),
        )

        assertEquals(3, summary.getInt("count"))
        assertTrue(summary.getBoolean("enabled"))
        assertEquals(1.5, summary.getDouble("ratio"), 0.0001)
    }

    @Test
    fun overallSummaryIsTruncated() {
        val arguments = JSONObject()
        repeat(10) { index ->
            arguments.put("key$index", "v".repeat(80))
        }

        val summary = McpArgumentSummarizer.summarize(arguments)

        assertEquals(241, summary.length)
        assertTrue(summary.endsWith("…"))
    }
}
