package com.benton.izukijs.mcp.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpAuditSanitizerTest {

    private fun sanitize(value: Any?): JSONObject =
        McpAuditSanitizer.sanitize(value, 0) as JSONObject

    @Test
    fun redactsSensitiveKeysRecursively() {
        val out = sanitize(
            JSONObject()
                .put("TOKEN", "abc")
                .put("nested", JSONObject().put("password", "p"))
                .put(
                    "list",
                    JSONArray().put(JSONObject().put("secret", "s")),
                ),
        )

        assertEquals("***", out.getString("TOKEN"))
        assertEquals("***", out.getJSONObject("nested").getString("password"))
        assertEquals(
            "***",
            out.getJSONArray("list").getJSONObject(0).getString("secret"),
        )
    }

    @Test
    fun truncatesLongStringsAndArrays() {
        val array = JSONArray().apply {
            repeat(60) { put("item$it") }
        }
        val out = sanitize(
            JSONObject()
                .put("text", "x".repeat(600))
                .put("items", array),
        )

        assertEquals(500, out.getString("text").length)
        assertEquals(50, out.getJSONArray("items").length())
    }

    @Test
    fun depthLimitReturnsEllipsis() {
        assertEquals("…", McpAuditSanitizer.sanitize(JSONObject(), 5))
    }

    @Test
    fun preservesScalarsAndNull() {
        val out = sanitize(
            JSONObject()
                .put("count", 3)
                .put("enabled", true)
                .put("missing", JSONObject.NULL),
        )

        assertEquals(3, out.getInt("count"))
        assertTrue(out.getBoolean("enabled"))
        assertEquals(JSONObject.NULL, out.get("missing"))
    }
}
