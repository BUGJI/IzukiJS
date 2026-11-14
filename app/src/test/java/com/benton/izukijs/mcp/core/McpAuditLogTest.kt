package com.benton.izukijs.mcp.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class McpAuditLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun newLog(): McpAuditLog = McpAuditLog(File(folder.root, "audit.jsonl"))

    @Test
    fun recordSanitizesArgumentsAndOmitsBlankMessage() {
        val log = newLog()

        log.record(
            tool = "click",
            arguments = JSONObject()
                .put("token", "abcdef")
                .put("x", 1)
                .put("nested", JSONObject().put("password", "p")),
            ok = true,
            message = "   ",
        )

        val entry = JSONObject(log.tail(1))
        assertEquals("click", entry.getString("tool"))
        assertTrue(entry.getBoolean("ok"))
        assertFalse(entry.has("message"))
        assertEquals("***", entry.getJSONObject("args").getString("token"))
        assertEquals(1, entry.getJSONObject("args").getInt("x"))
        assertEquals(
            "***",
            entry.getJSONObject("args").getJSONObject("nested").getString("password"),
        )
    }

    @Test
    fun tailReturnsLastLines() {
        val log = newLog()
        log.record("first", JSONObject(), ok = true, message = null)
        log.record("second", JSONObject(), ok = false, message = "boom")

        val lines = log.tail(1)

        assertFalse(lines.contains("first"))
        assertEquals("second", JSONObject(lines).getString("tool"))
    }

    @Test
    fun messageIsTruncated() {
        val log = newLog()

        log.record("tool", JSONObject(), ok = false, message = "e".repeat(600))

        assertEquals(500, JSONObject(log.tail(1)).getString("message").length)
    }

    @Test
    fun oversizedEntryReplacesArguments() {
        val log = newLog()
        val arguments = JSONObject().apply {
            repeat(20) { index -> put("key$index", "v".repeat(500)) }
        }

        log.record("tool", arguments, ok = true, message = null)

        assertEquals("…", JSONObject(log.tail(1)).get("args"))
    }
}
