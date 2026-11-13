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
        return sendTest(channel)
    }

    /** 发送测试消息到未保存的草稿渠道，便于新建时先验证配置。 */
    fun sendTest(channel: NotifyChannel): NotifyReport {
        val settings = settingsRepository.current()
        val now = System.currentTimeMillis()
        val event = NotifyEvent(
            level = NotifyLevel.INFO,
            kind = com.benton.izukijs.core.notification.NotifyKind.SCRIPT,
            title = "Izuki JS",
            text = appContext.getString(com.benton.izukijs.R.string.notif_test_text),
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
            NotifyChannelType.DINGTALK_GROUP -> sendDingtalkGroup(channel, event)
            NotifyChannelType.WEWORK_ROBOT -> sendWeworkRobot(channel, event)
            NotifyChannelType.FEISHU_ROBOT -> sendFeishuRobot(channel, event)
            NotifyChannelType.SERVERCHAN -> sendServerchan(channel, event)
            NotifyChannelType.PUSHPLUS -> sendPushplus(channel, event)
            NotifyChannelType.GOTIFY -> sendGotify(channel, event)
        }
    }

    private fun sendWebhook(channel: NotifyChannel, event: NotifyEvent) {
        val url = channel.url.trim()
        if (url.isEmpty()) throw NotifySendException("missing webhook url")
        val headers = HttpTransport.parseHeaders(channel.headers).toMutableMap()
        if (channel.contentType.isNotBlank()) headers["Content-Type"] = channel.contentType

        val timestamp = System.currentTimeMillis()
        val secret = channel.configValue("secret")
        val sign = if (secret.isNotEmpty()) {
            NotifySigning.urlEncode(
                NotifySigning.hmacSha256Base64(secret, "$timestamp\n$secret"),
            )
        } else {
            ""
        }
        val body = channel.bodyTemplate.takeIf { it.isNotBlank() }
            ?.let { renderWebhookTemplate(it, event, timestamp, sign) }
            ?: defaultWebhookBody(event, timestamp, sign)

        val method = channel.method.trim().uppercase().ifEmpty { "POST" }
        val noBodyMethod = method == "GET" || method == "HEAD"
        // GET / HEAD 无法携带请求体：把 `a=1&b=2` 形式追加到 URL。
        val requestUrl = if (noBodyMethod && body.isNotBlank() &&
            !body.startsWith("{") && !body.startsWith("[")
        ) {
            url + (if (url.contains('?')) "&" else "?") + body
        } else {
            url
        }
        val result = HttpTransport.request(
            url = requestUrl,
            method = method,
            headers = headers,
            body = body.takeIf { !noBodyMethod },
            timeoutMs = channel.timeoutMs,
        )
        requireOk(result)
        val expected = channel.configValue("response")
        if (expected.isNotEmpty() && !result.body.contains(expected)) {
            throw NotifySendException("response mismatch: ${result.body.take(120)}")
        }
    }

    private fun renderWebhookTemplate(
        template: String,
        event: NotifyEvent,
        timestamp: Long,
        sign: String,
    ): String = NotifyTemplate.render(template, event)
        .replace("{{timestamp}}", timestamp.toString())
        .replace("{{sign}}", sign)
        .replace("[timestamp]", timestamp.toString())
        .replace("[sign]", sign)
        .replace("[title]", event.title)
        .replace("[content]", event.text)
        .replace("[msg]", event.text)
        .replace("[from]", event.scriptName.orEmpty())

    private fun sendBark(channel: NotifyChannel, event: NotifyEvent) {
        if (channel.deviceKey.isBlank()) throw NotifySendException("missing Bark device key")
        val base = channel.url.trim().ifBlank { "https://api.day.app" }.trimEnd('/')
        val barkTitle = channel.configValue("title")
            .takeIf { it.isNotEmpty() }
            ?.let { NotifyTemplate.render(it, event) }
            ?.ifBlank { null }
            ?: event.title
        val body = JSONObject().apply {
            put("device_key", channel.deviceKey)
            put("title", barkTitle)
            put("body", event.text)
            put("isArchive", 1)
            if (channel.group.isNotBlank()) put("group", channel.group)
            if (channel.icon.isNotBlank()) put("icon", channel.icon)
            if (channel.sound.isNotBlank()) put("sound", channel.sound)
            channel.configValue("badge").takeIf { it.isNotEmpty() }?.let { put("badge", it) }
            val configuredLevel = channel.configValue("level")
            put(
                "level",
                if (configuredLevel.isNotEmpty()) {
                    configuredLevel
                } else {
                    when (event.level) {
                        NotifyLevel.INFO -> "passive"
                        else -> "active"
                    }
                },
            )
            channel.configValue("urlAction").takeIf { it.isNotEmpty() }?.let {
                put("url", NotifyTemplate.render(it, event))
            }
            channel.configValue("call").takeIf { it.isNotEmpty() }?.let { put("call", it) }

            val autoCopy = channel.configValue("autoCopy")
            if (autoCopy.isNotEmpty()) {
                put("copy", NotifyTemplate.render(autoCopy, event))
                put("autoCopy", 1)
            } else {
                detectVerificationCode(event.text)?.let {
                    put("copy", it)
                    put("autoCopy", 1)
                }
            }
        }

        val transformation = channel.configValue("transformation")
        val encryptionKey = channel.configValue("key")
        val encrypted = transformation.isNotEmpty() &&
            !transformation.equals("none", ignoreCase = true) &&
            encryptionKey.isNotEmpty()
        if (!encrypted) {
            requireOk(
                HttpTransport.request(
                    url = "$base/push",
                    contentType = "application/json",
                    body = body.toString(),
                    timeoutMs = channel.timeoutMs,
                ),
            )
            return
        }
        val ivLength = if (transformation.contains("GCM", ignoreCase = true)) 12 else 16
        val iv = channel.configValue("iv").ifBlank { NotifyCrypto.randomAlphaNum(ivLength) }
        val cipherText = NotifyCrypto.encryptBase64(
            plain = body.toString(),
            transformation = transformation,
            key = encryptionKey,
            iv = iv,
        )
        requireOk(
            HttpTransport.request(
                url = "$base/${channel.deviceKey.trim('/')}",
                contentType = "application/x-www-form-urlencoded",
                body = NotifySigning.formBody(mapOf("iv" to iv, "ciphertext" to cipherText)),
                timeoutMs = channel.timeoutMs,
            ),
        )
    }

    private val barkCodePattern = Regex(
        "验证码|授权码|校验码|检验码|确认码|激活码|动态码|安全码|验证代码|code|verification",
        RegexOption.IGNORE_CASE,
    )

    private val barkDigits = Regex("(?<!\\d)(\\d{4,8})(?!\\d)")

    private fun detectVerificationCode(text: String): String? {
        if (!barkCodePattern.containsMatchIn(text)) return null
        return barkDigits.find(text)?.groupValues?.getOrNull(1)
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

    private fun sendDingtalkGroup(channel: NotifyChannel, event: NotifyEvent) {
        val token = channel.configValue("token")
        if (token.isEmpty()) throw NotifySendException("missing DingTalk token")
        var url = if (token.startsWith("http", ignoreCase = true)) {
            token
        } else {
            "https://oapi.dingtalk.com/robot/send?access_token=$token"
        }
        val secret = channel.configValue("secret")
        if (secret.isNotEmpty()) {
            val timestamp = System.currentTimeMillis()
            val sign = NotifySigning.urlEncode(
                NotifySigning.hmacSha256Base64(secret, "$timestamp\n$secret"),
            )
            url += (if (url.contains('?')) "&" else "?") + "timestamp=$timestamp&sign=$sign"
        }
        val markdown = channel.configValue("msgtype").equals("markdown", ignoreCase = true)
        val body = JSONObject().apply {
            if (markdown) {
                put("msgtype", "markdown")
                put(
                    "markdown",
                    JSONObject().apply {
                        put("title", channel.renderTitle(event))
                        put("text", joinTitleText(event))
                    },
                )
            } else {
                put("msgtype", "text")
                put("text", JSONObject().put("content", joinTitleText(event)))
            }
            put(
                "at",
                JSONObject().apply {
                    put("isAtAll", channel.configValue("atAll").toBoolean())
                    channel.configValue("atMobiles").splitList().takeIf { it.isNotEmpty() }
                        ?.let { put("atMobiles", JSONArray(it)) }
                    channel.configValue("atDingtalkIds").splitList().takeIf { it.isNotEmpty() }
                        ?.let { put("atDingtalkIds", JSONArray(it)) }
                },
            )
        }
        requireJsonField(
            HttpTransport.request(
                url = url,
                contentType = "application/json",
                body = body.toString(),
                timeoutMs = channel.timeoutMs,
            ),
            "errcode",
            0,
        )
    }

    private fun sendWeworkRobot(channel: NotifyChannel, event: NotifyEvent) {
        val webHook = channel.configValue("webHook")
        if (webHook.isEmpty()) throw NotifySendException("missing WeCom webhook")
        val markdown = channel.configValue("msgType").equals("markdown", ignoreCase = true)
        val body = JSONObject().apply {
            if (markdown) {
                put("msgtype", "markdown")
                put("markdown", JSONObject().put("content", joinTitleText(event)))
            } else {
                put("msgtype", "text")
                put(
                    "text",
                    JSONObject().apply {
                        put("content", joinTitleText(event))
                        channel.configValue("atUserIds").splitList().takeIf { it.isNotEmpty() }
                            ?.let { put("mentioned_list", JSONArray(it)) }
                        channel.configValue("atMobiles").splitList().takeIf { it.isNotEmpty() }
                            ?.let { put("mentioned_mobile_list", JSONArray(it)) }
                    },
                )
            }
        }
        requireJsonField(
            HttpTransport.request(
                url = webHook,
                contentType = "application/json",
                body = body.toString(),
                timeoutMs = channel.timeoutMs,
            ),
            "errcode",
            0,
        )
    }

    private fun sendFeishuRobot(channel: NotifyChannel, event: NotifyEvent) {
        val webHook = channel.configValue("webhook")
        if (webHook.isEmpty()) throw NotifySendException("missing Feishu webhook")
        val timestamp = System.currentTimeMillis() / 1000
        val body = JSONObject().apply { put("timestamp", timestamp) }
        val secret = channel.configValue("secret")
        if (secret.isNotEmpty()) {
            val stringToSign = "$timestamp\n$secret"
            body.put("sign", NotifySigning.hmacSha256Base64(stringToSign, ""))
        }
        val text = chipText(channel, event)
        if (channel.configValue("msgType").equals("interactive", ignoreCase = true)) {
            body.put("msg_type", "interactive")
            body.put("card", feishuCard(channel, event))
        } else {
            body.put("msg_type", "text")
            body.put("content", JSONObject().put("text", text))
        }
        requireFeishu(
            HttpTransport.request(
                url = webHook,
                contentType = "application/json",
                body = body.toString(),
                timeoutMs = channel.timeoutMs,
            ),
        )
    }

    private fun sendServerchan(channel: NotifyChannel, event: NotifyEvent) {
        val sendKey = channel.configValue("sendKey")
        if (sendKey.isEmpty()) throw NotifySendException("missing ServerChan send key")
        val digits = Regex("^sctp(\\d+)t").find(sendKey)?.groupValues?.getOrNull(1)
        val url = if (digits != null) {
            "https://$digits.push.ft07.com/send/$sendKey.send"
        } else {
            "https://sctapi.ftqq.com/$sendKey.send"
        }
        val form = NotifySigning.formBody(
            mapOf(
                "title" to channel.renderTitle(event),
                "desp" to event.text,
                "channel" to channel.configValue("channel"),
                "group" to channel.configValue("openid"),
            ),
        )
        requireJsonField(
            HttpTransport.request(
                url = url,
                contentType = "application/x-www-form-urlencoded",
                body = form,
                timeoutMs = channel.timeoutMs,
            ),
            "code",
            0,
        )
    }

    private fun sendPushplus(channel: NotifyChannel, event: NotifyEvent) {
        val token = channel.configValue("token")
        if (token.isEmpty()) throw NotifySendException("missing PushPlus token")
        val website = channel.configValue("website").ifBlank { "www.pushplus.plus" }
        val body = JSONObject().apply {
            put("token", token)
            put("title", channel.renderTitle(event))
            put("content", event.text)
            channel.configValue("template").takeIf { it.isNotEmpty() }?.let { put("template", it) }
            channel.configValue("topic").takeIf { it.isNotEmpty() }?.let { put("topic", it) }
            if (website == "www.pushplus.plus") {
                channel.configValue("channel").takeIf { it.isNotEmpty() }?.let { put("channel", it) }
                channel.configValue("webhook").takeIf { it.isNotEmpty() }?.let { put("webhook", it) }
                channel.configValue("callbackUrl").takeIf { it.isNotEmpty() }
                    ?.let { put("callbackUrl", it) }
                channel.configValue("validTime").toLongOrNull()?.let {
                    put("timestamp", System.currentTimeMillis() + it * 1000L)
                }
            }
        }
        requireJsonField(
            HttpTransport.request(
                url = "https://$website/send",
                contentType = "application/json",
                body = body.toString(),
                timeoutMs = channel.timeoutMs,
            ),
            "code",
            200,
        )
    }

    private fun sendGotify(channel: NotifyChannel, event: NotifyEvent) {
        val webServer = channel.configValue("webServer")
        val token = channel.configValue("token")
        if (webServer.isEmpty()) throw NotifySendException("missing Gotify server")
        if (token.isEmpty()) throw NotifySendException("missing Gotify token")
        val url = "${webServer.trimEnd('/')}/message?token=$token"
        val form = NotifySigning.formBody(
            mapOf(
                "title" to channel.renderTitle(event),
                "message" to event.text,
                "priority" to channel.configValue("priority"),
            ),
        )
        val result = HttpTransport.request(
            url = url,
            contentType = "application/x-www-form-urlencoded",
            body = form,
            timeoutMs = channel.timeoutMs,
        )
        if (!result.ok) {
            throw NotifySendException("HTTP ${result.status}: ${result.body.take(160)}")
        }
        val json = runCatching { JSONObject(result.body) }.getOrNull()
        if (json == null || !json.has("id")) {
            throw NotifySendException("invalid Gotify response: ${result.body.take(120)}")
        }
    }

    private fun NotifyChannel.configValue(key: String): String = config[key].orEmpty().trim()

    private fun NotifyChannel.renderTitle(event: NotifyEvent): String {
        val template = configValue("titleTemplate")
        val base = if (template.isNotEmpty()) NotifyTemplate.render(template, event) else event.title
        return base.ifBlank { event.title }.ifBlank { "Izuki JS" }
    }

    private fun joinTitleText(event: NotifyEvent): String =
        listOf(event.title, event.text).filter { it.isNotBlank() }.joinToString("\n")

    private fun String.splitList(): List<String> =
        split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun chipText(channel: NotifyChannel, event: NotifyEvent): String {
        val prefix = buildString {
            if (channel.configValue("atAll").toBoolean()) append("<at user_id=\"all\"></at> ")
            channel.configValue("atOpenIds").splitList().forEach {
                append("<at user_id=\"$it\"></at> ")
            }
        }
        return prefix + joinTitleText(event)
    }

    private fun feishuCard(channel: NotifyChannel, event: NotifyEvent): JSONObject {
        val custom = channel.configValue("messageCard")
        if (custom.isNotEmpty()) {
            runCatching { JSONObject(NotifyTemplate.render(custom, event)) }.getOrNull()?.let { return it }
        }
        return JSONObject().apply {
            put("config", JSONObject().put("wide_screen_mode", true))
            put(
                "header",
                JSONObject().put(
                    "title",
                    JSONObject().apply {
                        put("tag", "plain_text")
                        put("content", channel.renderTitle(event))
                    },
                ),
            )
            put(
                "elements",
                JSONArray().put(
                    JSONObject().apply {
                        put("tag", "div")
                        put(
                            "text",
                            JSONObject().apply {
                                put("tag", "lark_md")
                                put("content", event.text)
                            },
                        )
                    },
                ),
            )
        }
    }

    private fun requireJsonField(result: HttpTransport.Result, field: String, expected: Int) {
        if (!result.ok) throw NotifySendException("HTTP ${result.status}: ${result.body.take(160)}")
        val json = runCatching { JSONObject(result.body) }.getOrNull()
            ?: throw NotifySendException("invalid response: ${result.body.take(120)}")
        val code = json.optInt(field, Int.MIN_VALUE)
        if (code != expected) {
            val detail = json.optString("errmsg").ifBlank { json.optString("msg") }.take(120)
            throw NotifySendException("$field=$code $detail".trim())
        }
    }

    private fun requireFeishu(result: HttpTransport.Result) {
        if (!result.ok) throw NotifySendException("HTTP ${result.status}: ${result.body.take(160)}")
        val json = runCatching { JSONObject(result.body) }.getOrNull()
            ?: throw NotifySendException("invalid response: ${result.body.take(120)}")
        val code = when {
            json.has("code") -> json.optInt("code", -1)
            json.has("StatusCode") -> json.optInt("StatusCode", -1)
            else -> 0
        }
        if (code != 0) {
            val detail = json.optString("msg")
                .ifBlank { json.optString("StatusMessage") }
                .take(120)
            throw NotifySendException("code=$code $detail".trim())
        }
    }

    private fun defaultWebhookBody(
        event: NotifyEvent,
        timestamp: Long,
        sign: String,
    ): String = JSONObject().apply {
        put("title", event.title)
        put("text", event.text)
        put("level", event.level.key)
        put("kind", event.kind.key)
        put("source", event.source.key)
        put("script", event.scriptName.orEmpty())
        put("status", event.runStatus.orEmpty())
        put("durationMs", event.durationMs)
        put("time", event.timestamp)
        put("timestamp", timestamp)
        if (sign.isNotEmpty()) put("sign", sign)
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
