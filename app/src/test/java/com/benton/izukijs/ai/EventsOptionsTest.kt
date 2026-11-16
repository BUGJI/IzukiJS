package com.benton.izukijs.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP / AI 的 `events` 工具参数归一化：snake_case 转成脚本 API 使用的 camelCase。
 */
class EventsOptionsTest {

    @Test
    fun mapsSnakeCaseKeys() {
        val options = eventsOptions(
            JSONObject(
                """{"action":"wait","timeout_ms":2500,"quiet_ms":300,"text_contains":"确定"}""",
            ),
        )

        assertFalse(options.has("action"))
        assertFalse(options.has("timeout_ms"))
        assertFalse(options.has("quiet_ms"))
        assertFalse(options.has("text_contains"))
        assertEquals(2_500.0, options.getDouble("timeoutMs"), 0.0)
        assertEquals(300L, options.getLong("quietMs"))
        assertEquals("确定", options.getString("textContains"))
    }

    @Test
    fun keepsUnrelatedOptions() {
        val options = eventsOptions(
            JSONObject("""{"action":"list","types":["toast"],"limit":5,"sinceSeq":9}"""),
        )

        assertEquals(1, options.getJSONArray("types").length())
        assertEquals("toast", options.getJSONArray("types").getString(0))
        assertEquals(5, options.getInt("limit"))
        assertEquals(9L, options.getLong("sinceSeq"))
    }

    @Test
    fun doesNotMutateCallerArguments() {
        val args = JSONObject("""{"action":"wait","timeout_ms":1000}""")

        eventsOptions(args)

        assertTrue(args.has("action"))
        assertTrue(args.has("timeout_ms"))
        assertFalse(args.has("timeoutMs"))
    }

    @Test
    fun toleratesMissingOptions() {
        val options = eventsOptions(JSONObject("{}"))

        assertEquals(0, options.length())
    }
}
