package com.benton.izukijs.runtime.api

import android.content.Context
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationManagerCompat
import com.benton.izukijs.service.NotificationItem
import com.benton.izukijs.service.NotificationListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通知监听 API。挂在全局 `notify` 命名空间，只读 + 清除。
 *
 * 需用户在「通知使用权」中授予本应用。仅读取通知标题 / 内容 / 包名 / 时间，不点击、不回复。
 */
class NotificationApi(private val context: Context) {

    @JavascriptInterface
    fun available(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** 当前活动通知列表，JSON 数组。 */
    @JavascriptInterface
    fun list(): String {
        val array = JSONArray()
        NotificationListener.Store.snapshot().forEach { array.put(it.toJson()) }
        return array.toString()
    }

    /**
     * 等待某应用的新通知（在调用之后 post 的），返回其 JSON；超时 / 无匹配返回 null。
     * 阻塞式轮询，超时上限 [MAX_TIMEOUT_MS]。
     */
    @JavascriptInterface
    fun latest(packageName: String, timeoutMs: Double): String? {
        if (!available()) return null
        val started = System.currentTimeMillis()
        val timeout = timeoutMs.toLong().coerceIn(0L, MAX_TIMEOUT_MS)
        while (true) {
            val hit = NotificationListener.Store.snapshot()
                .firstOrNull { it.packageName == packageName && it.postTime >= started }
            if (hit != null) return hit.toJson().toString()
            if (System.currentTimeMillis() - started >= timeout) return null
            runCatching { Thread.sleep(POLL_INTERVAL_MS) }
        }
    }

    /** 按 key 清除一条通知。 */
    @JavascriptInterface
    fun clear(key: String): Boolean = NotificationListener.cancelByKey(key)

    private fun NotificationItem.toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("package", packageName)
        put("title", title)
        put("text", text)
        put("subText", subText)
        put("postTime", postTime)
        put("ongoing", ongoing)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 150L
        const val MAX_TIMEOUT_MS = 60_000L
    }
}
