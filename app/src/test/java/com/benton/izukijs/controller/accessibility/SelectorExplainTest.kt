package com.benton.izukijs.controller.accessibility

import com.benton.izukijs.controller.NodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorExplainTest {

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
        children = listOf(
            NodeSnapshot(
                className = "android.widget.TextView",
                text = "登录",
                viewId = null,
                contentDescription = null,
                left = 20,
                top = 100,
                right = 200,
                bottom = 150,
                clickable = false,
                editable = false,
                children = emptyList(),
                depth = 1,
                indexInParent = 0,
            ),
            NodeSnapshot(
                className = "android.widget.Button",
                text = "登录",
                viewId = "com.example:id/login",
                contentDescription = null,
                left = 20,
                top = 200,
                right = 200,
                bottom = 260,
                clickable = true,
                editable = false,
                children = emptyList(),
                depth = 1,
                indexInParent = 1,
            ),
        ),
    )

    @Test
    fun explainReportsCumulativeMatchesAndEmptyCondition() {
        val criteria = SelectorDsl.parse("text~=登录 clickable=true")
        val result = SelectorExplain.explain(tree, criteria)
        assertTrue(result.getBoolean("ok"))
        assertEquals(3, result.getInt("totalNodes"))
        val conditions = result.getJSONArray("conditions")
        assertEquals("textContains", conditions.getJSONObject(0).getString("key"))
        assertEquals(2, conditions.getJSONObject(0).getInt("matches"))
        assertEquals("clickable", conditions.getJSONObject(1).getString("key"))
        assertEquals(1, conditions.getJSONObject(1).getInt("matches"))
        assertEquals(1, result.getJSONArray("samples").length())

        val missing = SelectorExplain.explain(tree, SelectorDsl.parse("text~=不存在"))
        assertEquals("textContains", missing.getString("firstEmpty"))
    }

    @Test
    fun codegenPrefersUniqueIdOrText() {
        val byId = SelectorCodegen.generate(
            tree,
            SelectorDsl.parse("id=com.example:id/login"),
        )
        assertTrue(byId.getBoolean("ok"))
        assertTrue(byId.getBoolean("unique"))
        assertEquals("id=com.example:id/login", byId.getString("dsl"))

        val byText = SelectorCodegen.generate(
            tree,
            SelectorDsl.parse("text=登录 clickable=true"),
        )
        assertTrue(byText.getBoolean("ok"))
        assertTrue(byText.getBoolean("unique"))
        assertTrue(byText.getString("dsl").isNotBlank())
    }

    @Test
    fun codegenReportsNoMatch() {
        val result = SelectorCodegen.generate(tree, SelectorDsl.parse("text=不存在"))
        assertFalse(result.getBoolean("ok"))
    }
}
