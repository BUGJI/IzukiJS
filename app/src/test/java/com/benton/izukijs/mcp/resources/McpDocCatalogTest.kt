package com.benton.izukijs.mcp.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpDocCatalogTest {

    private val assets = mutableMapOf(
        "MCP.md" to "# MCP 服务\n\n资源与工具说明。",
        "ARCHITECTURE.md" to "# 架构\n\n分层与运行链路。",
        "images/logo.png" to "not markdown",
        "notes.txt" to "# 忽略",
    )

    private fun catalog(
        names: () -> List<String> = { assets.keys.toList() },
    ) = McpDocCatalog(
        assetNames = names,
        assetReader = { name -> assets[name] },
    )

    @Test
    fun indexIsSortedAndUsesFirstHeading() {
        val index = catalog().indexJson()

        assertTrue(index.getBoolean("ok"))
        assertEquals(2, index.getInt("count"))
        assertEquals(McpDocCatalog.NOTE, index.getString("note"))

        val docs = index.getJSONArray("docs")
        assertEquals("ARCHITECTURE", docs.getJSONObject(0).getString("id"))
        assertEquals("ARCHITECTURE.md", docs.getJSONObject(0).getString("name"))
        assertEquals("架构", docs.getJSONObject(0).getString("title"))
        assertEquals("izuki://docs/ARCHITECTURE", docs.getJSONObject(0).getString("uri"))
        assertEquals("MCP", docs.getJSONObject(1).getString("id"))
    }

    @Test
    fun indexFallsBackToFileIdWhenHeadingMissing() {
        assets["PLAIN.md"] = "没有一级标题的正文"

        val doc = catalog().indexJson().getJSONArray("docs")
            .let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
            .first { it.getString("id") == "PLAIN" }

        assertEquals("PLAIN", doc.getString("title"))
    }

    @Test
    fun readPagesTextAndReportsNextOffset() {
        assets["LONG.md"] = "x".repeat(2_500)
        val pages = catalog().read("LONG", offset = 0, maxChars = 1_000)!!

        assertTrue(pages.getBoolean("ok"))
        assertEquals(2_500, pages.getInt("total_chars"))
        assertEquals(1_000, pages.getInt("returned_chars"))
        assertTrue(pages.getBoolean("has_more"))
        assertEquals(1_000, pages.getInt("next_offset"))

        val last = catalog().read("LONG", offset = 2_000, maxChars = 1_000)!!
        assertEquals(500, last.getInt("returned_chars"))
        assertFalse(last.getBoolean("has_more"))
        assertFalse(last.has("next_offset"))
    }

    @Test
    fun readClampsPagingBounds() {
        assets["LONG.md"] = "x".repeat(2_500)

        assertEquals(
            McpDocCatalog.MIN_MAX_CHARS,
            catalog().read("LONG", maxChars = 1)!!.getInt("returned_chars"),
        )
        assertEquals(
            2_500,
            catalog().read("LONG", maxChars = Int.MAX_VALUE)!!.getInt("returned_chars"),
        )
    }

    @Test
    fun readPastEndReturnsEmptyPage() {
        val page = catalog().read("MCP", offset = 10_000)!!

        assertEquals("", page.getString("text"))
        assertEquals(0, page.getInt("returned_chars"))
        assertFalse(page.getBoolean("has_more"))
    }

    @Test
    fun readAcceptsIdFileNameAndQueryVariants() {
        val variants = listOf("MCP", "mcp", "MCP.md", " mcp.md?x=1 ", "MCP.md#资源")

        variants.forEach { name ->
            val doc = catalog().read(name)
            assertNotNull("应能读取：$name", doc)
            assertEquals("MCP", doc!!.getString("id"))
        }
    }

    @Test
    fun readRejectsUnknownAndTraversalNames() {
        val badNames = listOf("", "  ", "..", "../MCP.md", "docs/MCP", "docs\\MCP", "missing")

        badNames.forEach { name ->
            assertNull("应拒绝：$name", catalog().read(name))
        }
    }

    @Test
    fun unreadableAssetsAreSkipped() {
        val broken = McpDocCatalog(
            assetNames = { listOf("A.md", "B.md") },
            assetReader = { name ->
                if (name == "A.md") error("读取失败") else null
            },
        )

        assertEquals(0, broken.indexJson().getInt("count"))
        assertNull(broken.read("A"))
    }

    @Test
    fun emptyAssetsYieldEmptyIndex() {
        val empty = McpDocCatalog(assetNames = { emptyList() }, assetReader = { null })

        val index = empty.indexJson()
        assertEquals(0, index.getInt("count"))
        assertEquals(0, index.getJSONArray("docs").length())
        assertNull(empty.read("anything"))
    }
}
