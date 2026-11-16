package com.benton.izukijs.runtime.api

import com.benton.izukijs.service.AccessibilityEventStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `events` 脚本 API 的参数解析、过滤与失败返回结构。
 *
 * 可用性通过构造参数注入，避免 JVM 单测触碰无障碍服务。
 */
class EventsApiTest {

    @Before
    fun setUp() {
        AccessibilityEventStore.clear()
    }

    @After
    fun tearDown() {
        AccessibilityEventStore.clear()
    }

    private fun availableApi(startSeq: Long = 0L, exit: () -> Boolean = { false }) =
        EventsApi(isExitRequested = exit, startSeq = startSeq, isAvailable = { true })

    private fun unavailableApi() =
        EventsApi(startSeq = 0L, isAvailable = { false })

    @Test
    fun listReturnsNewestFirstAndFiltersByType() {
        AccessibilityEventStore.record(type = "window_state", text = "first", time = 1_000L)
        AccessibilityEventStore.record(type = "key_down", text = "second", time = 2_000L)

        val all = JSONArray(availableApi().listRaw("{}"))
        assertEquals(2, all.length())
        assertEquals("key_down", all.getJSONObject(0).getString("type"))
        assertEquals("window_state", all.getJSONObject(1).getString("type"))

        val filtered = JSONArray(availableApi().listRaw("""{"types":["window_state"]}"""))
        assertEquals(1, filtered.length())
        assertEquals("window_state", filtered.getJSONObject(0).getString("type"))
    }

    @Test
    fun listHonorsSinceSeqAndClampsLimit() {
        repeat(5) { index ->
            AccessibilityEventStore.record(type = "view_clicked", text = "e$index", time = index.toLong())
        }
        val since = JSONArray(
            availableApi().listRaw("""{"sinceSeq":${AccessibilityEventStore.sequence() - 1},"limit":99}"""),
        )
        assertEquals(1, since.length())

        // limit 0 会被钳到 1，超大值会被钳到内部上限。
        val clamped = JSONArray(availableApi().listRaw("""{"limit":0}"""))
        assertEquals(1, clamped.length())
        val large = JSONArray(availableApi().listRaw("""{"limit":9999}"""))
        assertEquals(5, large.length())
    }

    @Test
    fun invalidJsonFallsBackToDefaultOptions() {
        AccessibilityEventStore.record(type = "toast", text = "hi", time = 1_000L)

        val array = JSONArray(availableApi().listRaw("{not-json"))

        assertEquals(1, array.length())
        assertEquals("toast", array.getJSONObject(0).getString("type"))
    }

    @Test
    fun currentWindowReturnsLatestWindowEvent() {
        AccessibilityEventStore.record(
            type = "window_state",
            packageName = "com.example",
            className = "MainActivity",
            windowId = 7,
            time = 1_000L,
        )
        AccessibilityEventStore.record(type = "key_down", time = 2_000L)

        val window = JSONObject(availableApi().currentWindowRaw()!!)

        assertEquals("com.example", window.getString("package"))
        assertEquals("MainActivity", window.getString("className"))
        assertEquals(7, window.getInt("windowId"))
    }

    @Test
    fun currentWindowReturnsNullWithoutWindowEvents() {
        AccessibilityEventStore.record(type = "view_clicked", time = 1_000L)
        assertNull(availableApi().currentWindowRaw())
    }

    @Test
    fun clearEmptiesBufferButKeepsSequence() {
        AccessibilityEventStore.record(type = "view_clicked", time = 1_000L)
        val before = AccessibilityEventStore.sequence()

        assertTrue(availableApi().clear())

        assertEquals(0, AccessibilityEventStore.snapshot().size)
        assertEquals(before, AccessibilityEventStore.sequence())
    }

    @Test
    fun statusReportsAvailabilityCountAndSequence() {
        AccessibilityEventStore.record(type = "view_clicked", time = 1_000L)

        val status = JSONObject(availableApi().status())

        assertTrue(status.getBoolean("available"))
        assertTrue(status.getBoolean("connected"))
        assertEquals(1, status.getInt("count"))
        assertEquals(AccessibilityEventStore.sequence(), status.getLong("seq"))
    }

    @Test
    fun waitReturnsExistingEventWhenAvailable() {
        AccessibilityEventStore.record(type = "toast", text = "saved", time = 1_000L)

        val hit = availableApi().waitRaw("""{"timeoutMs":10}""")

        assertTrue(hit != null)
        assertEquals("toast", JSONObject(hit!!).getString("type"))
    }

    @Test
    fun waitReturnsNullWhenAccessibilityUnavailable() {
        assertNull(unavailableApi().waitRaw("{}"))
    }

    @Test
    fun waitIdleReportsUnavailableAsFailureJson() {
        val result = JSONObject(unavailableApi().waitIdleRaw("{}"))

        assertFalse(result.getBoolean("ok"))
        assertEquals("无障碍服务未连接", result.getString("reason"))
    }

    @Test
    fun waitIdleIsImmediatelyQuietWithoutEvents() {
        val result = JSONObject(availableApi().waitIdleRaw("""{"quietMs":50,"timeoutMs":50}"""))

        assertTrue(result.getBoolean("ok"))
        assertEquals(50L, result.getLong("quietMs"))
    }
}
