package com.benton.izukijs.notification

import android.content.Context
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.notification.NotifyChannel
import com.benton.izukijs.core.notification.NotifyChannelType
import com.benton.izukijs.core.notification.NotifyEvent
import com.benton.izukijs.core.notification.NotifyFilters
import com.benton.izukijs.core.notification.NotifyLevel
import com.benton.izukijs.core.notification.NotifyTemplate
import com.benton.izukijs.core.notification.NotificationSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** 单个渠道的发送结果。 */
data class NotifyDelivery(
    val channelId: String,
    val channelName: String,
    val ok: Boolean,
    val error: String? = null,
)

/** 一次分发的汇总结果。 */
data class NotifyReport(
    val delivered: List<NotifyDelivery> = emptyList(),
    val failed: List<NotifyDelivery> = emptyList(),
    val skipped: Boolean = false,
    val noChannel: Boolean = false,
) {
    val ok: Boolean get() = delivered.isNotEmpty() && failed.isEmpty()

    fun toJson(): String = JSONObject().apply {
        put("ok", ok)
        put(
            "delivered",
            JSONArray().apply { delivered.forEach { put(it.channelId) } },
        )
        put(
            "failed",
            JSONArray().apply {
                failed.forEach { item ->
                    put(
                        JSONObject().apply {
                            put("channel", item.channelId)
                            put("error", item.error.orEmpty())
                        },
                    )
                }
            },
        )
        if (skipped) put("skipped", true)
        if (noChannel) put("noChannel", true)
    }.toString()
}

/**
 * 通知分发器：按全局开关与渠道过滤，把事件并行投递到本地与远程渠道。
 *
 * 发送是阻塞式的（[HttpURLConnection]），[dispatch] 会丢到 IO 线程；
 * 脚本 API 需要同步结果时直接调用 [dispatchBlocking]。
 */
class NotificationDispatcher(
    context: Context,
    private val settingsRepository: NotificationSettingsRepository,
    private val logBus: LogBus,
) {

    private val appContext = context.applicationContext
    private val localNotifier = LocalNotifier(appContext, logBus)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rateWindows = HashMap<String, ArrayDeque<Long>>()
    private val rateLock = Any()

    fun currentSettings(): NotificationSettings = settingsRepository.current()

    fun dispatch(event: NotifyEvent) {
        scope.launch { runCatching { dispatchBlocking(event) } }
    }

    fun dispatchBlocking(
        event: NotifyEvent,
        targetChannelIds: Set<String>? = null,
    ): NotifyReport {
        val settings = settingsRepository.current()
        if (!NotifyFilters.eventAllowed(settings, event)) {
            return NotifyReport(skipped = true)
        }
        val channels = settings.channels
            .asSequence()
            .filter { targetChannelIds == null || it.id in targetChannelIds }
            .filter { NotifyFilters.channelAccepts(it, event) }
            .toList()
        if (channels.isEmpty()) return NotifyReport(noChannel = true)

        val enriched = enrich(event, settings)
        val delivered = mutableListOf<NotifyDelivery>()
        val failed = mutableListOf<NotifyDelivery>()
        channels.forEach { channel ->
            val result = sendWithRetry(channel, enriched, settings)
            if (result.ok) delivered += result else failed += result
        }
        return NotifyReport(delivered, failed)
    }

    /** 发送测试消息；跳过全局开关与级别过滤，直接投递到指定渠道。 */
    fun sendTest(channelId: String): NotifyReport {
        val settings = settingsRepository.current()
        val channel = settings.channels.firstOrNull { it.id == channelId }
            ?: return NotifyReport(failed = emptyList(), noChannel = true)
        val now = System.currentTimeMillis()
        val event = NotifyEvent(
            level = NotifyLevel.INFO,
            kind = com.benton.izukijs.core.notification.NotifyKind.SCRIPT,
            title = "Izuki JS",
            text = "通知渠道测试",
            source = com.benton.izukijs.core.notification.NotifySource.SCRIPT,
            timestamp = now,
        )
        val result = sendWithRetry(channel, event, settings, enforceRateLimit = false)
        return if (result.ok) {
            NotifyReport(delivered = listOf(result))
        } else {
            NotifyReport(failed = listOf(result))
        }
    }

    private fun enrich(event: NotifyEvent, settings: NotificationSettings): NotifyEvent {
        if (!settings.includeErrorTail) return event.copy(errorTail = null)
        val tail = event.errorTail
            ?.takeLast(settings.errorTailChars.coerceAtLeast(0))
            ?.takeIf { it.isNotBlank() }
            ?: return event
        return event.copy(
            errorTail = tail,
            text = listOf(event.text, tail).filter { it.isNotBlank() }.joinToString("\n"),
        )
    }

    private fun sendWithRetry(
        channel: NotifyChannel,
        event: NotifyEvent,
        settings: NotificationSettings,
        enforceRateLimit: Boolean = true,
    ): NotifyDelivery {
        if (enforceRateLimit && !rateAllowed(channel, settings.rateLimitPerMinute)) {
            logBus.warn("通知已限流: ${channel.label}")
            return NotifyDelivery(channel.id, channel.label, false, "rate limited")
        }
        val attempts = channel.retries.coerceIn(0, 5) + 1
        var last: NotifySendException? = null
        for (attempt in 0 until attempts) {
            try {
                sendOnce(channel, event)
                logBus.debug("通知已发送: ${channel.label} (${event.kind.key})")
                return NotifyDelivery(channel.id, channel.label, true)
            } catch (t: NotifySendException) {
                last = t
            } catch (t: Throwable) {
                last = NotifySendException(t.message ?: t.javaClass.simpleName, t)
            }
            if (attempt < attempts - 1) {
                runCatching { Thread.sleep((500L shl attempt).coerceAtMost(5_000L)) }
            }
        }
        val message = last?.message ?: "unknown error"
        logBus.warn("通知发送失败: ${channel.label} - ${message.take(120)}")
        return NotifyDelivery(channel.id, channel.label, false, message.take(200))
    }

    private fun rateAllowed(channel: NotifyChannel, perMinute: Int): Boolean {
        if (perMinute <= 0) return true
        val now = System.currentTimeMillis()
        synchronized(rateLock) {
            val window = rateWindows.getOrPut(channel.id) { ArrayDeque() }
            while (window.isNotEmpty() && now - window.first() > 60_000L) {
                window.removeFirst()
            }
            if (window.size >= perMinute) return false
            window.addLast(now)
            return true
        }
    }

    private fun sendOnce(channel: NotifyChannel, event: NotifyEvent) {
        when (channel.type) {
            NotifyChannelType.LOCAL -> localNotifier.notify(event)
            NotifyChannelType.WEBHOOK -> sendWebhook(channel, event)
            NotifyChannelType.BARK -> sendBark(channel, event)
            NotifyChannelType.TELEGRAM -> sendTelegram(channel, event)
            NotifyChannelType.NTFY -> sendNtfy(channel, event)
        }
    }

    private fun sendWebhook(channel: NotifyChannel, event: NotifyEvent) {
        val url = channel.url.trim()
        if (url.isEmpty()) throw NotifySendException("missing webhook url")
        val headers = HttpTransport.parseHeaders(channel.headers).toMutableMap()
        if (channel.contentType.isNotBlank()) headers["Content-Type"] = channel.contentType
        val body = channel.bodyTemplate.takeIf { it.isNotBlank() }
            ?.let { NotifyTemplate.render(it, event) }
            ?: defaultWebhookBody(event)
        requireOk(
            HttpTransport.request(
                url = url,
                method = channel.method,
                headers = headers,
                body = body,
                timeoutMs = channel.timeoutMs,
            ),
        )
    }

    private fun sendBark(channel: NotifyChannel, event: NotifyEvent) {
        if (channel.deviceKey.isBlank()) throw NotifySendException("missing Bark device key")
        val base = channel.url.trim().ifBlank { "https://api.day.app" }.trimEnd('/')
        val body = JSONObject().apply {
            put("device_key", channel.deviceKey)
            put("title", event.title)
            put("body", event.text)
            if (channel.group.isNotBlank()) put("group", channel.group)
            if (channel.icon.isNotBlank()) put("icon", channel.icon)
            if (channel.sound.isNotBlank()) put("sound", channel.sound)
            put(
                "level",
                when (event.level) {
                    NotifyLevel.ERROR, NotifyLevel.WARN -> "active"
                    NotifyLevel.SUCCESS -> "active"
                    NotifyLevel.INFO -> "passive"
                },
            )
        }
        requireOk(
            HttpTransport.request(
                url = "$base/push",
                contentType = "application/json",
                body = body.toString(),
                timeoutMs = channel.timeoutMs,
            ),
        )
    }

    private fun sendTelegram(channel: NotifyChannel, event: NotifyEvent) {
        if (channel.botToken.isBlank()) throw NotifySendException("missing Telegram bot token")
        if (channel.chatId.isBlank()) throw NotifySendException("missing Telegram chat id")
        val text = listOf(event.title, event.text)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val body = JSONObject().apply {
            put("chat_id", channel.chatId)
            put("text", text)
            put("disable_web_page_preview", true)
        }
        requireOk(
            HttpTransport.request(
                url = "https://api.telegram.org/bot${channel.botToken}/sendMessage",
                contentType = "application/json",
                body = body.toString(),
                timeoutMs = channel.timeoutMs,
            ),
        )
    }

    private fun sendNtfy(channel: NotifyChannel, event: NotifyEvent) {
        if (channel.topic.isBlank()) throw NotifySendException("missing ntfy topic")
        val base = channel.url.trim().ifBlank { "https://ntfy.sh" }.trimEnd('/')
        val headers = mutableMapOf("Title" to event.title.replace('\n', ' '))
        if (channel.priority.isNotBlank()) headers["Priority"] = channel.priority
        if (event.tags.isNotEmpty()) headers["Tags"] = event.tags.joinToString(",")
        if (channel.token.isNotBlank()) headers["Authorization"] = "Bearer ${channel.token}"
        requireOk(
            HttpTransport.request(
                url = "$base/${channel.topic.trim('/')}",
                contentType = "text/plain; charset=utf-8",
                headers = headers,
                body = event.text,
                timeoutMs = channel.timeoutMs,
            ),
        )
    }

    private fun defaultWebhookBody(event: NotifyEvent): String = JSONObject().apply {
        put("title", event.title)
        put("text", event.text)
        put("level", event.level.key)
        put("kind", event.kind.key)
        put("source", event.source.key)
        put("script", event.scriptName.orEmpty())
        put("status", event.runStatus.orEmpty())
        put("durationMs", event.durationMs)
        put("time", event.timestamp)
        if (!event.errorTail.isNullOrBlank()) put("error", event.errorTail)
        if (event.tags.isNotEmpty()) {
            put("tags", JSONArray().apply { event.tags.forEach { put(it) } })
        }
    }.toString()

    private fun requireOk(result: HttpTransport.Result) {
        if (!result.ok) {
            throw NotifySendException("HTTP ${result.status}: ${result.body.take(160)}")
        }
    }
}
