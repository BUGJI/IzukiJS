package com.benton.izukijs.core.notification

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 通知级别，数值越大越严重。 */
enum class NotifyLevel(val key: String, val rank: Int) {
    INFO("info", 0),
    SUCCESS("success", 1),
    WARN("warn", 2),
    ERROR("error", 3),
    ;

    companion object {
        fun fromKey(key: String?): NotifyLevel? =
            entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/** 通知事件类型，用于渠道过滤。 */
enum class NotifyKind(val key: String) {
    SCRIPT("script"),
    RUN_MANUAL("run_manual"),
    RUN_AUTO("run_auto"),
    FLOW_SUMMARY("flow_summary"),
    ;

    companion object {
        fun fromKey(key: String?): NotifyKind? =
            entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/** 事件来源。手动与自动的归类由此决定。 */
enum class NotifySource(val key: String) {
    MANUAL("manual"),
    TILE("tile"),
    SCHEDULE("schedule"),
    FLOW("flow"),
    MCP("mcp"),
    SCRIPT("script"),
    ;

    val automatic: Boolean
        get() = this == SCHEDULE || this == FLOW || this == MCP

    companion object {
        fun fromKey(key: String?): NotifySource? =
            entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/** 统一通知事件。脚本发送与运行结果都转换为该模型后交给分发器。 */
data class NotifyEvent(
    val level: NotifyLevel,
    val kind: NotifyKind,
    val title: String,
    val text: String,
    val source: NotifySource,
    val scriptId: String? = null,
    val scriptName: String? = null,
    val runId: String? = null,
    val runStatus: String? = null,
    val durationMs: Long = 0L,
    val errorTail: String? = null,
    val tags: Set<String> = emptySet(),
    val data: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
) {

    /** 本地通知的去重键：同一脚本的运行结果覆盖上一条，避免堆积。 */
    val dedupeKey: String
        get() = when (kind) {
            NotifyKind.RUN_MANUAL, NotifyKind.RUN_AUTO ->
                "run:${scriptId ?: scriptName ?: title}"

            NotifyKind.FLOW_SUMMARY -> "flow:${scriptName ?: title}"
            NotifyKind.SCRIPT ->
                data["key"] ?: "script:${scriptId ?: scriptName ?: title}"
        }
}

/** 推送渠道类型。LOCAL 走系统通知，其余走 HTTP。 */
enum class NotifyChannelType(val key: String) {
    LOCAL("local"),
    WEBHOOK("webhook"),
    BARK("bark"),
    TELEGRAM("telegram"),
    NTFY("ntfy"),
    ;

    companion object {
        fun fromKey(key: String?): NotifyChannelType? =
            entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/**
 * 一个推送渠道配置。
 *
 * [url] / [headers] / [deviceKey] / [botToken] / [token] 在持久化时经 Keystore 加密；
 * 导出备份时使用内存中的明文，与 AI / OCR 配置的现有行为一致。
 */
data class NotifyChannel(
    val id: String,
    val type: NotifyChannelType,
    val name: String = "",
    val enabled: Boolean = true,
    val minLevel: NotifyLevel = NotifyLevel.INFO,
    val kinds: Set<NotifyKind> = NotifyKind.entries.toSet(),
    val tags: Set<String> = emptySet(),
    val url: String = "",
    val method: String = "POST",
    val contentType: String = "application/json",
    val headers: String = "",
    val bodyTemplate: String = "",
    val deviceKey: String = "",
    val group: String = "",
    val icon: String = "",
    val sound: String = "",
    val botToken: String = "",
    val chatId: String = "",
    val topic: String = "",
    val token: String = "",
    val priority: String = "",
    val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    val retries: Int = 1,
) {

    val label: String
        get() = name.ifBlank { type.key }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000
        const val LOCAL_ID = "local"

        fun local() = NotifyChannel(
            id = LOCAL_ID,
            type = NotifyChannelType.LOCAL,
            enabled = true,
        )

        fun newChannel(type: NotifyChannelType): NotifyChannel = NotifyChannel(
            id = UUID.randomUUID().toString().take(8),
            type = type,
        )
    }
}

/** 通知系统的全局配置。渠道列表也保存在这里。 */
data class NotificationSettings(
    val enabled: Boolean = true,
    val scriptMessages: Boolean = true,
    val runManual: Boolean = true,
    val runAuto: Boolean = true,
    /** 运行结果通知的最低级别；默认仅失败（WARN 及以上）。 */
    val runMinLevel: NotifyLevel = NotifyLevel.WARN,
    val flowSummary: Boolean = true,
    val flowSteps: Boolean = false,
    val includeErrorTail: Boolean = true,
    val errorTailChars: Int = 300,
    val rateLimitPerMinute: Int = 20,
    val channels: List<NotifyChannel> = listOf(NotifyChannel.local()),
) {

    fun withDefaults(): NotificationSettings {
        val normalized = channels.map { it.copy(id = it.id.ifBlank { UUID.randomUUID().toString().take(8) }) }
            .distinctBy { it.id }
        val withLocal = if (normalized.any { it.type == NotifyChannelType.LOCAL }) {
            normalized
        } else {
            listOf(NotifyChannel.local()) + normalized
        }
        return copy(
            errorTailChars = errorTailChars.coerceIn(0, 2_000),
            rateLimitPerMinute = rateLimitPerMinute.coerceIn(0, 600),
            channels = withLocal,
        )
    }
}

/** 结果是明文模型；持久化层负责加密敏感字段。 */
object NotificationSettingsCodec {

    fun encode(settings: NotificationSettings): JSONObject = JSONObject().apply {
        put("enabled", settings.enabled)
        put("scriptMessages", settings.scriptMessages)
        put("runManual", settings.runManual)
        put("runAuto", settings.runAuto)
        put("runMinLevel", settings.runMinLevel.key)
        put("flowSummary", settings.flowSummary)
        put("flowSteps", settings.flowSteps)
        put("includeErrorTail", settings.includeErrorTail)
        put("errorTailChars", settings.errorTailChars)
        put("rateLimitPerMinute", settings.rateLimitPerMinute)
        put(
            "channels",
            JSONArray().apply {
                settings.channels.forEach { put(encodeChannel(it)) }
            },
        )
    }

    fun decode(json: JSONObject, base: NotificationSettings = NotificationSettings()): NotificationSettings {
        val channels = json.optJSONArray("channels")
            ?.let { array ->
                buildList {
                    for (i in 0 until array.length()) {
                        array.optJSONObject(i)?.let { decodeChannel(it) }?.let(::add)
                    }
                }
            }
            ?.takeIf { it.isNotEmpty() }
            ?: base.channels
        return NotificationSettings(
            enabled = json.optBoolean("enabled", base.enabled),
            scriptMessages = json.optBoolean("scriptMessages", base.scriptMessages),
            runManual = json.optBoolean("runManual", base.runManual),
            runAuto = json.optBoolean("runAuto", base.runAuto),
            runMinLevel = NotifyLevel.fromKey(json.optString("runMinLevel"))
                ?: base.runMinLevel,
            flowSummary = json.optBoolean("flowSummary", base.flowSummary),
            flowSteps = json.optBoolean("flowSteps", base.flowSteps),
            includeErrorTail = json.optBoolean("includeErrorTail", base.includeErrorTail),
            errorTailChars = json.optInt("errorTailChars", base.errorTailChars),
            rateLimitPerMinute = json.optInt("rateLimitPerMinute", base.rateLimitPerMinute),
            channels = channels,
        ).withDefaults()
    }

    private fun encodeChannel(channel: NotifyChannel): JSONObject = JSONObject().apply {
        put("id", channel.id)
        put("type", channel.type.key)
        put("name", channel.name)
        put("enabled", channel.enabled)
        put("minLevel", channel.minLevel.key)
        put("kinds", JSONArray().apply { channel.kinds.forEach { put(it.key) } })
        put("tags", JSONArray().apply { channel.tags.forEach { put(it) } })
        putIfNotEmpty("url", channel.url)
        put("method", channel.method)
        put("contentType", channel.contentType)
        putIfNotEmpty("headers", channel.headers)
        putIfNotEmpty("bodyTemplate", channel.bodyTemplate)
        putIfNotEmpty("deviceKey", channel.deviceKey)
        putIfNotEmpty("group", channel.group)
        putIfNotEmpty("icon", channel.icon)
        putIfNotEmpty("sound", channel.sound)
        putIfNotEmpty("botToken", channel.botToken)
        putIfNotEmpty("chatId", channel.chatId)
        putIfNotEmpty("topic", channel.topic)
        putIfNotEmpty("token", channel.token)
        putIfNotEmpty("priority", channel.priority)
        put("timeoutMs", channel.timeoutMs)
        put("retries", channel.retries)
    }

    private fun decodeChannel(json: JSONObject): NotifyChannel? {
        val type = NotifyChannelType.fromKey(json.optString("type")) ?: return null
        return NotifyChannel(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString().take(8) },
            type = type,
            name = json.optString("name"),
            enabled = json.optBoolean("enabled", true),
            minLevel = NotifyLevel.fromKey(json.optString("minLevel")) ?: NotifyLevel.INFO,
            kinds = json.optJSONArray("kinds")
                ?.let { array ->
                    buildSet {
                        for (i in 0 until array.length()) {
                            NotifyKind.fromKey(array.optString(i))?.let(::add)
                        }
                    }
                }
                ?.takeIf { it.isNotEmpty() }
                ?: NotifyKind.entries.toSet(),
            tags = json.optJSONArray("tags")
                ?.let { array ->
                    buildSet {
                        for (i in 0 until array.length()) {
                            array.optString(i).trim().takeIf { it.isNotEmpty() }?.let(::add)
                        }
                    }
                }
                .orEmpty(),
            url = json.optString("url"),
            method = json.optString("method", "POST"),
            contentType = json.optString("contentType", "application/json"),
            headers = json.optString("headers"),
            bodyTemplate = json.optString("bodyTemplate"),
            deviceKey = json.optString("deviceKey"),
            group = json.optString("group"),
            icon = json.optString("icon"),
            sound = json.optString("sound"),
            botToken = json.optString("botToken"),
            chatId = json.optString("chatId"),
            topic = json.optString("topic"),
            token = json.optString("token"),
            priority = json.optString("priority"),
            timeoutMs = json.optInt("timeoutMs", NotifyChannel.DEFAULT_TIMEOUT_MS)
                .coerceIn(1_000, 60_000),
            retries = json.optInt("retries", 1).coerceIn(0, 5),
        )
    }

    private fun JSONObject.putIfNotEmpty(key: String, value: String) {
        if (value.isNotEmpty()) put(key, value)
    }
}

/** 事件与渠道的过滤规则，抽成纯函数便于单测。 */
object NotifyFilters {

    fun eventAllowed(settings: NotificationSettings, event: NotifyEvent): Boolean {
        if (!settings.enabled) return false
        return when (event.kind) {
            NotifyKind.SCRIPT -> settings.scriptMessages
            NotifyKind.RUN_MANUAL ->
                settings.runManual && event.level.rank >= settings.runMinLevel.rank

            NotifyKind.RUN_AUTO ->
                settings.runAuto &&
                    event.level.rank >= settings.runMinLevel.rank &&
                    (event.source != NotifySource.FLOW || settings.flowSteps)

            NotifyKind.FLOW_SUMMARY ->
                settings.flowSummary && event.level.rank >= settings.runMinLevel.rank
        }
    }

    fun channelAccepts(channel: NotifyChannel, event: NotifyEvent): Boolean {
        if (!channel.enabled) return false
        if (event.level.rank < channel.minLevel.rank) return false
        if (event.kind !in channel.kinds) return false
        if (channel.tags.isEmpty()) return true
        return event.tags.any { it in channel.tags }
    }
}

/** 把事件字段替换进模板字符串。 */
object NotifyTemplate {

    fun render(template: String, event: NotifyEvent): String {
        var result = template
        replacements(event).forEach { (key, value) ->
            result = result.replace("{{$key}}", value)
        }
        event.data.forEach { (key, value) ->
            result = result.replace("{{data.$key}}", value)
        }
        return result
    }

    fun replacements(event: NotifyEvent): Map<String, String> = mapOf(
        "title" to event.title,
        "text" to event.text,
        "level" to event.level.key,
        "kind" to event.kind.key,
        "source" to event.source.key,
        "script" to event.scriptName.orEmpty(),
        "scriptId" to event.scriptId.orEmpty(),
        "status" to event.runStatus.orEmpty(),
        "duration" to formatDuration(event.durationMs),
        "time" to formatTime(event.timestamp),
        "error" to event.errorTail.orEmpty(),
        "runId" to event.runId.orEmpty(),
    )

    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "-"
        val seconds = durationMs / 1000.0
        return when {
            seconds < 1 -> "${durationMs}ms"
            seconds < 60 -> String.format(java.util.Locale.US, "%.1fs", seconds)
            else -> {
                val minutes = (seconds / 60).toInt()
                val rest = (seconds % 60).toInt()
                "${minutes}m ${rest}s"
            }
        }
    }

    fun formatTime(timeMillis: Long): String = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(timeMillis))
    }.getOrDefault("")
}
