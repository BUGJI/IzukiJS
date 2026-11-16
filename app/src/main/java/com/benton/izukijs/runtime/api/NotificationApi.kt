package com.benton.izukijs.runtime.api

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.notification.ScriptNotificationCenter
import com.benton.izukijs.service.IzukiAccessibilityService
import com.benton.izukijs.service.NotificationItem
import com.benton.izukijs.service.NotificationListener
import com.benton.izukijs.service.ToastItem
import com.benton.izukijs.service.ToastObserver
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
    /** 当前脚本 runId；非空时只允许它改写运行通知，旧 run 不能覆盖新 run。 */
    private val ownerRunId: String? = null,
    /** 停止脚本时让 toast 等待循环尽快退出。 */
    private val isExitRequested: () -> Boolean = { false },
) {

    /** 为单次脚本运行创建带归属的轻量副本，避免共享 bundle 里的实例无法区分来源。 */
    fun forRun(runId: String?, isExitRequested: () -> Boolean = this.isExitRequested): NotificationApi =
        NotificationApi(context, logBus, progressSettingEnabled, runId, isExitRequested)

    @JavascriptInterface
    fun available(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** 通知监听服务当前是否真的已连接（区别于仅授予权限）。 */
    @JavascriptInterface
    fun connected(): Boolean = NotificationListener.isConnected()

    /** 当前通知监听状态，供脚本 / MCP 区分「未授权」「未绑定」「真的没有通知」。 */
    @JavascriptInterface
    fun status(): String = JSONObject().apply {
        put("available", available())
        put("connected", NotificationListener.isConnected())
        put("count", NotificationListener.Store.snapshot().size)
    }.toString()

    /** 当前活动通知列表，JSON 数组。 */
    @JavascriptInterface
    fun list(): String {
        ensureListener()
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
        val rebound = ensureListener()
        val snapshot = NotificationListener.Store.snapshot()
        val all = snapshot
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
            put("listener_connected", NotificationListener.isConnected())
            put("rebind_attempted", rebound)
            if (selected.size < all.size) put("truncated", true)
            if (all.isEmpty()) {
                put(
                    "empty_reason",
                    when {
                        !NotificationListener.isConnected() -> "通知监听未连接（已尝试重新绑定）"
                        NotificationListener.isConnected() -> "通知监听已连接，当前确实没有匹配通知"
                        else -> "当前没有匹配通知"
                    },
                )
            }
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
        ensureListener()
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

    /** 点击通知的 contentIntent（高风险：可能打开敏感页面）。 */
    @JavascriptInterface
    fun click(key: String): Boolean = NotificationListener.clickByKey(key)

    /** 删除通知：优先触发应用的 deleteIntent，没有时系统取消。 */
    @JavascriptInterface
    fun delete(key: String): Boolean = NotificationListener.deleteByKey(key)

    // ---- Toast 读取 ----
    //
    // Toast 不走 NotificationListenerService，只能从无障碍的
    // TYPE_NOTIFICATION_STATE_CHANGED 事件里解析，因此可用性看无障碍连接，
    // 与「通知使用权」无关。

    /** 无障碍服务是否已连接；toast 读取在未连接时全部降级为空 / null。 */
    @JavascriptInterface
    fun toastAvailable(): Boolean = IzukiAccessibilityService.isConnected

    /** toast 缓冲状态，区分「无障碍未连接」与「已连接但还没有 toast」。 */
    @JavascriptInterface
    fun toastStatus(): String = JSONObject().apply {
        put("available", toastAvailable())
        put("connected", ToastObserver.connected)
        put("count", ToastObserver.count())
    }.toString()

    /** 最近的 toast 数组，新的在前；[limit] 为 0 时由 prelude 补默认值。 */
    @JavascriptInterface
    fun toasts(limit: Int): String = JSONArray().apply {
        ToastObserver.snapshot()
            .take(limit.coerceIn(0, MAX_TOAST_LIMIT))
            .forEach { put(it.toJson()) }
    }.toString()

    /**
     * 等待调用之后出现、包名匹配且文本包含指定片段的 toast。
     *
     * options: `{package?, contains?, timeoutMs?}`。无障碍未连接、超时或脚本被
     * 停止时返回 null，不抛异常。
     */
    @JavascriptInterface
    fun latestToastRaw(optionsJson: String): String? {
        if (!toastAvailable()) return null
        val options = runCatching {
            JSONObject(optionsJson.ifBlank { "{}" })
        }.getOrNull() ?: JSONObject()
        val packageName = options.optString("package").trim().ifBlank { null }
        val contains = options.optString("contains").trim().ifBlank { null }
        val timeout = options.optDouble("timeoutMs", DEFAULT_TOAST_TIMEOUT_MS.toDouble())
            .toLong()
            .coerceIn(0L, MAX_TOAST_TIMEOUT_MS)
        val started = System.currentTimeMillis()
        while (true) {
            if (isExitRequested()) return null
            val hit = ToastObserver.snapshot()
                .firstOrNull { it.matches(packageName, contains, started) }
            if (hit != null) return hit.toJson().toString()
            if (System.currentTimeMillis() - started >= timeout) return null
            runCatching { Thread.sleep(TOAST_POLL_INTERVAL_MS) }
        }
    }

    /** 清空 toast 缓冲。 */
    @JavascriptInterface
    fun clearToasts(): Boolean {
        ToastObserver.clear()
        return true
    }

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
        return ScriptNotificationCenter.update(
            optionsJson,
            allowProgress = true,
            runId = ownerRunId,
        )
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
            runId = ownerRunId,
        )
    }

    /** 清除进度样式，保留标题 / 文本 / 停止按钮。 */
    @JavascriptInterface
    fun reset(): Boolean {
        if (!ScriptNotificationCenter.isActive) {
            logBus?.debug("notify.reset 已忽略：脚本运行通知未启动")
            return false
        }
        return ScriptNotificationCenter.reset(ownerRunId)
    }

    /**
     * 在权限已授予但服务未连接 / 缓冲为空时请求一次重绑，并有限等待连接。
     *
     * 重绑有全局冷却，避免 MCP 轮询或脚本循环把系统绑定请求打爆。返回本次是否
     * 真正发起了重绑。
     */
    private fun ensureListener(): Boolean {
        if (!available()) return false
        val connected = NotificationListener.isConnected()
        if (connected && NotificationListener.Store.snapshot().isNotEmpty()) return false
        val now = SystemClock.elapsedRealtime()
        val last = LAST_REBIND_AT.get()
        if (now - last < REBIND_COOLDOWN_MS) return false
        if (!LAST_REBIND_AT.compareAndSet(last, now)) return false
        NotificationListener.requestRebind(context)

        val deadline = SystemClock.elapsedRealtime() + REBIND_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (NotificationListener.isConnected()) break
            runCatching { Thread.sleep(REBIND_POLL_MS) }
        }
        if (NotificationListener.isConnected()) {
            NotificationListener.refreshActive()
        }
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

    private fun ToastItem.toJson(): JSONObject = JSONObject().apply {
        put("package", packageName)
        put("text", text)
        put(
            "texts",
            JSONArray().apply {
                texts.forEach { put(it) }
            },
        )
        put("className", className)
        put("time", time)
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
        const val REBIND_WAIT_MS = 700L
        const val REBIND_POLL_MS = 50L
        const val REBIND_COOLDOWN_MS = 10_000L

        const val DEFAULT_TOAST_TIMEOUT_MS = 10_000L
        const val MAX_TOAST_TIMEOUT_MS = 60_000L
        const val TOAST_POLL_INTERVAL_MS = 100L
        const val MAX_TOAST_LIMIT = 200

        val LAST_REBIND_AT = java.util.concurrent.atomic.AtomicLong(0L)
    }
}
