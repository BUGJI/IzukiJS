package com.benton.izukijs.runtime.api

import com.benton.izukijs.controller.NodeSnapshot
import com.benton.izukijs.core.log.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `explain/codegen` 复用调用方控件树快照的入口。
 *
 * 算法本身由 SelectorExplainTest 覆盖；这里验证新入口的接线与失败返回。
 */
class SelectorApiWithTreeTest {

    private val logBus = LogBus(
        clock = { 1_000L },
        elapsed = { 0L },
        logcat = { _, _ -> },
        emitIntervalMs = 0L,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    // 没有无障碍后端：所有带 tree 的调用都不应回落到 controller()。
    private val api = SelectorApi(logBus, controllerProvider = { null })

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
                indexInParent = 0,
            ),
        ),
    )

    @Test
    fun explainUsesProvidedTree() {
        val result = JSONObject(api.explainRawWithTree(tree, "text~=登录"))

        assertTrue(result.getBoolean("ok"))
        assertEquals(2, result.getInt("totalNodes"))
        assertEquals(1, result.getJSONArray("samples").length())
    }

    @Test
    fun codegenUsesProvidedTree() {
        val result = JSONObject(api.codegenRawWithTree(tree, "id=com.example:id/login"))

        assertTrue(result.getBoolean("ok"))
        assertTrue(result.getBoolean("unique"))
        assertEquals("id=com.example:id/login", result.getString("dsl"))
    }

    @Test
    fun missingTreeAndControllerReturnsError() {
        val explain = JSONObject(api.explainRawWithTree(null, "text=登录"))
        val codegen = JSONObject(api.codegenRawWithTree(null, "text=登录"))

        assertFalse(explain.getBoolean("ok"))
        assertTrue(explain.getString("error").contains("无法读取控件树"))
        assertFalse(codegen.getBoolean("ok"))
        assertTrue(codegen.getString("error").contains("无法读取控件树"))
    }

    @Test
    fun invalidCriteriaReturnsError() {
        val explain = JSONObject(api.explainRawWithTree(tree, "{not-json"))
        val codegen = JSONObject(api.codegenRawWithTree(tree, "{not-json"))

        assertFalse(explain.getBoolean("ok"))
        assertEquals("选择器条件无效", explain.getString("error"))
        assertFalse(codegen.getBoolean("ok"))
        assertEquals("选择器条件无效", codegen.getString("error"))
    }

    @Test
    fun dumpWithoutAccessibilityReturnsError() {
        val result = JSONObject(api.dumpRaw("{}"))

        assertFalse(result.getBoolean("ok"))
        assertTrue(result.getString("error").contains("无法读取控件树"))
        assertEquals("[]", api.windowsRaw())
    }
}
