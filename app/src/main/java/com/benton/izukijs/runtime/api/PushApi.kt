package com.benton.izukijs.runtime.api

import android.webkit.JavascriptInterface
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.notification.NotifyEvent
import com.benton.izukijs.core.notification.NotifyKind
import com.benton.izukijs.core.notification.NotifyLevel
import com.benton.izukijs.core.notification.NotifySource
import com.benton.izukijs.notification.NotificationDispatcher
import org.json.JSONArray
import org.json.JSONObject

/**
 * 脚本通知 API。挂在全局 `push` 命名空间：
 *
 * - 只负责「发出去」，不需要通知使用权，只受 `POST_NOTIFICATIONS` 与通知系统开关约束；
 * - 与 `notify`（通知监听 / 运行进度）解耦，使用 `// @need push` 才会暴露。
 */
class PushApi(
    private val dispatcher: NotificationDispatcher,
    private val logBus: LogBus,
    private val scriptId: String,
    private val scriptName: String,
) {

    @JavascriptInterface
    fun available(): Boolean = dispatcher.currentSettings().enabled

    /** 当前渠道概要，JSON 数组；不包含任何密钥字段。 */
    @JavascriptInterface
    fun channels(): String {
        val settings = dispatcher.currentSettings()
        return JSONArray().apply {
            settings.channels.forEach { channel ->
                put(
                    JSONObject().apply {
                        put("id", channel.id)
                        put("name", channel.label)
                        put("type", channel.type.key)
                        put("enabled", channel.enabled)
                    },
                )
            }
        }.toString()
    }

    /**
     * 发送一条通知，返回 `{ok, delivered, failed}`。
     *
     * options: `{title, text, level?, channels?, tags?, data?, key?}`。
     */
    @JavascriptInterface
    fun send(optionsJson: String): String {
        val options = runCatching { JSONObject(optionsJson) }.getOrNull()
            ?: return error("invalid options json")
        if (!dispatcher.currentSettings().enabled) return error("notification system disabled")

        val title = options.optString("title").trim()
        val text = options.optString("text").trim()
        if (title.isEmpty() && text.isEmpty()) return error("title or text is required")

        val level = NotifyLevel.fromKey(options.optString("level")) ?: NotifyLevel.INFO
        val tags = buildSet {
            add(NotifyKind.SCRIPT.key)
            options.stringSet("tags").forEach { add(it) }
        }
        val data = buildMap {
            options.optJSONObject("data")?.let { obj ->
                obj.keys().forEach { key -> put(key, obj.optString(key)) }
            }
            options.optString("key").trim().takeIf { it.isNotEmpty() }?.let { put("key", it) }
        }
        val channelIds = options.stringSet("channels").takeIf { it.isNotEmpty() }

        val event = NotifyEvent(
            level = level,
            kind = NotifyKind.SCRIPT,
            title = title.ifEmpty { scriptName.ifBlank { "Izuki JS" } },
            text = text.ifEmpty { title },
            source = NotifySource.SCRIPT,
            scriptId = scriptId,
            scriptName = scriptName,
            tags = tags,
            data = data,
        )
        val report = runCatching { dispatcher.dispatchBlocking(event, channelIds) }
            .getOrElse { t ->
                logBus.warn("push.send 失败: ${t.message}")
                return error(t.message ?: "send failed")
            }
        return report.toJson()
    }

    private fun error(message: String): String = JSONObject().apply {
        put("ok", false)
        put("error", message)
        put("delivered", JSONArray())
        put("failed", JSONArray())
    }.toString()

    private fun JSONObject.stringSet(key: String): Set<String> {
        val array = optJSONArray(key)
        if (array != null) {
            return buildSet {
                for (i in 0 until array.length()) {
                    array.optString(i).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        }
        val raw = optString(key)
        return raw.split(',', ';', ' ', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}
