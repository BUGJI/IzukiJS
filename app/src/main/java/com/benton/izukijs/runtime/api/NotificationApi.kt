package com.benton.izukijs.runtime.api

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.notification.ScriptNotificationCenter
import com.benton.izukijs.service.NotificationItem
import com.benton.izukijs.service.NotificationListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通知 API。挂在全局 `notify` 命名空间：
 *
 * - 读取 / 等待 / 清除通知，需「通知使用权」；
 * - `notify.progress` / `notify.update` / `notify.reset` 更新脚本运行通知，
 *   需 `POST_NOTIFICATIONS`，并受「设置 → 通用 → 启用进度通知」控制。
 */
class NotificationApi(
    private val context: Context,
    private val logBus: LogBus? = null,
    private val progressSettingEnabled: () -> Boolean = { true },
) {

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
     * 带过滤 / 分页的通知查询，返回 `{ok, total, count, notifications}`。
     *
     * [detail] 为 false 时只返回 package / title / text / postTime / ongoing，避免一次调用吃满上下文。
     */
    fun query(
        packageName: String?,
        limit: Int,
        since: Long,
        excludeOngoing: Boolean,
        detail: Boolean,
    ): String {
        val all = NotificationListener.Store.snapshot()
            .asSequence()
            .filter { packageName == null || it.packageName == packageName }
            .filter { since <= 0L || it.postTime >= since }
            .filter { !excludeOngoing || !it.ongoing }
            .sortedByDescending { it.postTime }
            .toList()
        val selected = all.take(limit.coerceAtLeast(0))
        return JSONObject().apply {
            put("ok", true)
            put("total", all.size)
            put("count", selected.size)
            if (selected.size < all.size) put("truncated", true)
            packageName?.let { put("package", it) }
            put(
                "notifications",
                JSONArray().apply {
                    selected.forEach { put(it.toJson(detail)) }
                },
            )
        }.toString()
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

    // ---- 脚本运行通知 ----

    /** 通知权限 + 进度通知开关是否都就绪。 */
    @JavascriptInterface
    fun progressEnabled(): Boolean = canPostNotifications() && progressSettingEnabled()

    /** 完整更新进度通知，JSON 对象；关闭进度通知或缺少权限时返回 false。 */
    @JavascriptInterface
    fun progressRaw(optionsJson: String): Boolean {
        if (!canPostNotifications()) {
            logBus?.debug("notify.progress 已忽略：未授予通知权限")
            return false
        }
        if (!progressSettingEnabled()) {
            logBus?.debug("notify.progress 已忽略：进度通知未启用")
            return false
        }
        if (!ScriptNotificationCenter.isActive) {
            logBus?.warn("notify.progress 已忽略：脚本运行通知未启动")
            return false
        }
        return ScriptNotificationCenter.update(optionsJson, allowProgress = true)
    }

    /** 局部更新标题 / 文本 / 阶段；进度开关关闭时忽略进度相关字段。 */
    @JavascriptInterface
    fun updateRaw(optionsJson: String): Boolean {
        if (!canPostNotifications()) {
            logBus?.debug("notify.update 已忽略：未授予通知权限")
            return false
        }
        if (!ScriptNotificationCenter.isActive) {
            logBus?.warn("notify.update 已忽略：脚本运行通知未启动")
            return false
        }
        return ScriptNotificationCenter.update(
            optionsJson,
            allowProgress = progressSettingEnabled(),
        )
    }

    /** 清除进度样式，保留标题 / 文本 / 停止按钮。 */
    @JavascriptInterface
    fun reset(): Boolean {
        if (!ScriptNotificationCenter.isActive) {
            logBus?.debug("notify.reset 已忽略：脚本运行通知未启动")
            return false
        }
        ScriptNotificationCenter.reset()
        return true
    }

    private fun canPostNotifications(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun NotificationItem.toJson(detail: Boolean = true): JSONObject = JSONObject().apply {
        if (detail) put("key", key)
        put("package", packageName)
        put("title", title)
        put("text", text)
        if (detail) put("subText", subText)
        put("postTime", postTime)
        put("ongoing", ongoing)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 150L
        const val MAX_TIMEOUT_MS = 60_000L
    }
}
