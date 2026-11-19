package com.benton.izukijs.runtime.api

import com.benton.izukijs.controller.NodeSnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorDumpTest {

    private val tree = NodeSnapshot(
        className = "android.widget.FrameLayout",
        text = null,
        viewId = "com.example:id/root",
        contentDescription = null,
        left = 0,
        top = 0,
        right = 400,
        bottom = 800,
        clickable = false,
        editable = false,
        packageName = "com.example",
        children = listOf(
            NodeSnapshot(
                className = "android.widget.Button",
                text = "登录",
                viewId = "com.example:id/login",
                contentDescription = null,
                left = 20,
                top = 200,
                right = 220,
                bottom = 260,
                clickable = true,
                editable = false,
                children = listOf(
                    NodeSnapshot(
                        className = "android.widget.TextView",
                        text = "隐藏",
                        viewId = null,
                        contentDescription = null,
                        left = 0,
                        top = 0,
                        right = 10,
                        bottom = 10,
                        clickable = false,
                        editable = false,
                        children = emptyList(),
                        visible = false,
                        depth = 2,
                    ),
                ),
                depth = 1,
            ),
        ),
    )

    @Test
    fun rendersAutoJsShapeWithDerivedFields() {
        val result = SelectorDump.render(tree, SelectorDump.Options())
        val root = result.getJSONObject("tree")

        assertTrue(result.getBoolean("ok"))
        assertEquals(3, result.getInt("total"))
        assertEquals(1, root.getInt("childCount"))
        assertEquals("com.example", root.getString("packageName"))
        assertEquals(400, root.getJSONObject("bounds").getInt("right"))
        assertTrue(root.isNull("idHex"))
        val button = root.getJSONArray("children").getJSONObject(0)
        assertEquals("登录", button.getString("text"))
        assertEquals(1, button.getInt("depth"))
        assertEquals(1, button.getJSONArray("actions").length())
        assertEquals("click", button.getJSONArray("actions").getString(0))
    }

    @Test
    fun maxDepthZeroKeepsOnlyRoot() {
        val result = SelectorDump.render(tree, SelectorDump.Options(maxDepth = 0))
        val root = result.getJSONObject("tree")

        assertEquals(1, result.getInt("total"))
        assertEquals(0, root.getJSONArray("children").length())
        assertTrue(result.getBoolean("truncated"))
    }

    @Test
    fun maxNodesStopsTraversal() {
        val result = SelectorDump.render(tree, SelectorDump.Options(maxNodes = 2))
        val root = result.getJSONObject("tree")

        assertEquals(2, result.getInt("total"))
        assertEquals(1, root.getJSONArray("children").length())
        assertTrue(result.getBoolean("truncated"))
    }

    @Test
    fun visibleOnlyDropsInvisibleSubtree() {
        val result = SelectorDump.render(tree, SelectorDump.Options(visibleOnly = true))
        val button = result.getJSONObject("tree").getJSONArray("children").getJSONObject(0)

        assertEquals(2, result.getInt("total"))
        assertEquals(0, button.getJSONArray("children").length())
        assertFalse(result.getBoolean("truncated"))
    }

    @Test
    fun propertiesRestrictKeysButKeepChildren() {
        val options = SelectorDump.parseOptions("""{"properties":["text","clickable","unknown"]}""")
        val root = SelectorDump.render(tree, options).getJSONObject("tree")
        val keys = root.keys().asSequence().toSet()

        assertEquals(setOf("text", "clickable", "children"), keys)
        assertNull(if (root.isNull("text")) null else root.getString("text"))
        assertTrue(root.has("children"))
    }

    @Test
    fun invalidOptionsFallBackToDefaults() {
        val options = SelectorDump.parseOptions("{not-json")

        assertEquals(-1, options.maxDepth)
        assertEquals(0, options.maxNodes)
        assertFalse(options.visibleOnly)
        assertTrue(options.properties.isEmpty())
    }
}
