package com.benton.izukijs.runtime.api

import android.webkit.JavascriptInterface
import com.benton.izukijs.service.AccessibilityEventStore
import com.benton.izukijs.service.A11yEvent
import com.benton.izukijs.service.IzukiAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * 无障碍事件 API。挂在 `events` 命名空间。
 *
 * 事件由 [IzukiAccessibilityService] 写入 [AccessibilityEventStore]，脚本侧只做
 * 快照 / 阻塞等待。QuickJS 不跨线程回调，因此没有 `events.on(...)`，用
 * `wait` / `waitIdle` 代替。
 */
class EventsApi(
    private val isExitRequested: () -> Boolean = { false },
    /** 本次 run 的起点；默认从创建时开始，MCP 可传 0 读取最近缓冲。 */
    private val startSeq: Long = AccessibilityEventStore.sequence(),
    /** 无障碍是否可用；抽成 lambda 便于脱离 Android 单测等待类接口的失败返回。 */
    private val isAvailable: () -> Boolean = { IzukiAccessibilityService.isConnected },
) {

    @JavascriptInterface
    fun available(): Boolean = isAvailable()

    @JavascriptInterface
    fun status(): String = JSONObject().apply {
        put("available", available())
        put("connected", isAvailable())
        put("seq", AccessibilityEventStore.sequence())
        put("count", AccessibilityEventStore.snapshot(0L, null, MAX_LIMIT).size)
    }.toString()

    /** 最近事件数组，新的在前。 */
    @JavascriptInterface
    fun listRaw(optionsJson: String): String {
        val options = parseOptions(optionsJson)
        val array = JSONArray()
        AccessibilityEventStore.snapshot(
            sinceSeq = options.optLong("sinceSeq", startSeq),
            types = parseTypes(options),
            limit = options.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT),
        ).forEach { array.put(it.toJson()) }
        return array.toString()
    }

    /**
     * 等待第一条匹配事件。
     *
     * options: `{types?, package?, textContains?, sinceSeq?, timeoutMs?}`。
     * 超时、无障碍不可用或脚本被停止时返回 null。
     */
    @JavascriptInterface
    fun waitRaw(optionsJson: String): String? {
        if (!available()) return null
        val options = parseOptions(optionsJson)
        val types = parseTypes(options)
        val packageName = options.optString("package").trim().ifBlank { null }
        val contains = options.optString("textContains").trim().ifBlank { null }
        var cursor = options.optLong("sinceSeq", startSeq)
        val timeout = options.optDouble("timeoutMs", DEFAULT_TIMEOUT_MS.toDouble())
            .toLong()
            .coerceIn(0L, MAX_TIMEOUT_MS)
        val deadline = System.currentTimeMillis() + timeout
        while (true) {
            if (isExitRequested()) return null
            val before = AccessibilityEventStore.sequence()
            val snapshot = AccessibilityEventStore.snapshot(cursor, types, MAX_LIMIT)
            // snapshot 新的在前；等待应按时间顺序返回最早的一条。
            val hit = snapshot.asReversed().firstOrNull { event ->
                (packageName == null || event.packageName == packageName) &&
                    (contains == null ||
                        event.text.contains(contains) ||
                        event.contentDescription?.contains(contains) == true)
            }
            cursor = before
            if (hit != null) return hit.toJson().toString()
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) return null
            AccessibilityEventStore.awaitChange(
                before,
                minOf(remaining, POLL_INTERVAL_MS),
            )
        }
    }

    /** 等待界面安静 [quietMs]；返回 `{ok, quietMs, elapsedMs, seq}`。 */
    @JavascriptInterface
    fun waitIdleRaw(optionsJson: String): String {
        if (!available()) {
            return JSONObject().apply {
                put("ok", false)
                put("reason", "无障碍服务未连接")
            }.toString()
        }
        val options = parseOptions(optionsJson)
        val types = parseTypes(options)
        val quietMs = options.optLong("quietMs", DEFAULT_QUIET_MS).coerceIn(MIN_QUIET_MS, MAX_QUIET_MS)
        val timeout = options.optDouble("timeoutMs", DEFAULT_TIMEOUT_MS.toDouble())
            .toLong()
            .coerceIn(0L, MAX_TIMEOUT_MS)
        val started = System.currentTimeMillis()
        val deadline = started + timeout
        while (true) {
            if (isExitRequested()) {
                return idleResult(false, quietMs, started, "脚本已停止")
            }
            val now = System.currentTimeMillis()
            val last = AccessibilityEventStore.lastEventAt(types)
            if (last == 0L || now - last >= quietMs) {
                return idleResult(true, quietMs, started, null)
            }
            val remaining = deadline - now
            if (remaining <= 0L) {
                return idleResult(false, quietMs, started, "等待界面安静超时")
            }
            val waitFor = minOf(remaining, (quietMs - (now - last)).coerceAtLeast(1L))
            AccessibilityEventStore.awaitChange(AccessibilityEventStore.sequence(), waitFor)
        }
    }

    /** 最近一次窗口事件的窗口信息。 */
    @JavascriptInterface
    fun currentWindowRaw(): String? {
        val event = AccessibilityEventStore.snapshot(
            0L,
            setOf("window_state", "window_content"),
            1,
        ).firstOrNull() ?: return null
        return JSONObject().apply {
            put("package", event.packageName ?: JSONObject.NULL)
            put("className", event.className ?: JSONObject.NULL)
            put("windowId", event.windowId)
            put("time", event.time)
        }.toString()
    }

    @JavascriptInterface
    fun clear(): Boolean {
        AccessibilityEventStore.clear()
        return true
    }

    private fun idleResult(ok: Boolean, quietMs: Long, started: Long, reason: String?): String =
        JSONObject().apply {
            put("ok", ok)
            put("quietMs", quietMs)
            put("elapsedMs", (System.currentTimeMillis() - started).coerceAtLeast(0L))
            put("seq", AccessibilityEventStore.sequence())
            if (reason != null) put("reason", reason)
        }.toString()

    private fun parseOptions(json: String): JSONObject =
        runCatching { JSONObject(json.ifBlank { "{}" }) }.getOrNull() ?: JSONObject()

    private fun parseTypes(options: JSONObject): Set<String>? {
        val array = options.optJSONArray("types")
            ?: options.optJSONArray("type")
        if (array != null) {
            val set = LinkedHashSet<String>()
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf { it.isNotEmpty() }?.let(set::add)
            }
            return set.ifEmpty { null }
        }
        val single = options.optString("type").trim()
        return single.takeIf { it.isNotEmpty() }?.let { setOf(it) }
    }

    private fun A11yEvent.toJson(): JSONObject = JSONObject().apply {
        put("seq", seq)
        put("type", type)
        put("package", packageName ?: JSONObject.NULL)
        put("className", className ?: JSONObject.NULL)
        put("text", text)
        put("desc", contentDescription ?: JSONObject.NULL)
        put("windowId", windowId)
        put("time", time)
        keyCode?.let { put("keyCode", it) }
    }

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val MAX_TIMEOUT_MS = 60_000L
        const val POLL_INTERVAL_MS = 200L
        const val DEFAULT_QUIET_MS = 500L
        const val MIN_QUIET_MS = 50L
        const val MAX_QUIET_MS = 10_000L
    }
}
