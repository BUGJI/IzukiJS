package com.benton.izukijs.mcp.tools

import com.benton.izukijs.mcp.resources.McpDocCatalog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpDocToolsTest {

    private val scriptApiPrefix = "# 脚本 API\n\n"
    private val assets = mapOf(
        "SCRIPT_API.md" to scriptApiPrefix + "x".repeat(20_000),
        "ARCHITECTURE.md" to "# 架构\n\n分层。",
    )

    private val docs = McpDocCatalog(
        assetNames = { assets.keys.toList() },
        assetReader = { name -> assets[name] },
    )

    private val tool = docsTool(docs)

    private fun call(arguments: JSONObject): JSONObject {
        val result = tool.call(arguments)
        assertFalse(result.isError)
        return JSONObject(result.content.first().text!!)
    }

    @Test
    fun listIsDefaultAction() {
        val result = call(JSONObject())

        assertTrue(result.getBoolean("ok"))
        assertEquals(2, result.getInt("count"))
        assertEquals(McpDocCatalog.NOTE, result.getString("note"))
    }

    @Test
    fun readReturnsFirstPageWithNextOffset() {
        val result = call(JSONObject().put("action", "read").put("name", "SCRIPT_API"))

        assertTrue(result.getBoolean("ok"))
        assertEquals("SCRIPT_API", result.getString("id"))
        assertEquals(McpDocCatalog.DEFAULT_MAX_CHARS, result.getInt("returned_chars"))
        assertTrue(result.getBoolean("has_more"))
        assertEquals(McpDocCatalog.DEFAULT_MAX_CHARS, result.getInt("next_offset"))
    }

    @Test
    fun readHonoursOffsetAndMaxChars() {
        val offset = 12_000
        val result = call(
            JSONObject()
                .put("action", "read")
                .put("name", "SCRIPT_API.md")
                .put("offset", offset)
                .put("max_chars", 1_000),
        )

        assertTrue(result.getBoolean("ok"))
        assertEquals(offset, result.getInt("offset"))
        assertEquals(1_000, result.getInt("returned_chars"))
        assertTrue(result.getBoolean("has_more"))
        assertEquals(offset + 1_000, result.getInt("next_offset"))
    }

    @Test
    fun readLastPageStops() {
        val total = scriptApiPrefix.length + 20_000
        val result = call(
            JSONObject()
                .put("action", "read")
                .put("name", "SCRIPT_API")
                .put("offset", total - 5),
        )

        assertTrue(result.getBoolean("ok"))
        assertEquals(5, result.getInt("returned_chars"))
        assertFalse(result.getBoolean("has_more"))
    }

    @Test
    fun missingNameReturnsAvailableDocs() {
        val result = call(JSONObject().put("action", "read"))

        assertFalse(result.getBoolean("ok"))
        val available = result.getJSONArray("available")
        val ids = (0 until available.length()).map { available.getString(it) }
        assertEquals(listOf("ARCHITECTURE", "SCRIPT_API"), ids)
    }

    @Test
    fun unknownActionFailsWithoutThrowing() {
        val result = call(JSONObject().put("action", "write"))

        assertFalse(result.getBoolean("ok"))
        assertTrue(result.getString("message").contains("list"))
    }
}
