package com.benton.izukijs.core.notification

/**
 * 单个渠道字段的声明式规格。
 *
 * [key] 是 [NotifyChannel.config] 中的键，[label] 是 UI 侧用于查字符串资源的稳定标识，
 * 因此本类不依赖 Android 资源，可直接单测。
 */
data class ChannelFieldSpec(
    val key: String,
    val label: String,
    val secret: Boolean = false,
    val required: Boolean = false,
    val multiline: Boolean = false,
    val options: List<String> = emptyList(),
    val defaultValue: String = "",
)

/** 一种渠道类型包含的字段集合与校验规则。 */
data class ChannelTypeSpec(
    val type: NotifyChannelType,
    val fields: List<ChannelFieldSpec>,
) {

    val secretKeys: Set<String>
        get() = fields.filter { it.secret }.map { it.key }.toSet()

    fun isValid(config: Map<String, String>): Boolean =
        fields.filter { it.required }.all { config[it.key].orEmpty().isNotBlank() }
}

/**
 * 新增 HTTP 渠道的字段注册表，驱动设置页动态表单、保存校验与密钥加密。
 * 旧的 LOCAL / WEBHOOK / BARK / TELEGRAM / NTFY 仍使用各自的专用字段与手写 UI。
 */
object NotifyChannelSpecs {

    private val BOOL = listOf("false", "true")

    private val specs: Map<NotifyChannelType, ChannelTypeSpec> = listOf(
        ChannelTypeSpec(
            type = NotifyChannelType.DINGTALK_GROUP,
            fields = listOf(
                ChannelFieldSpec("token", "token", secret = true, required = true),
                ChannelFieldSpec("secret", "secret", secret = true),
                ChannelFieldSpec(
                    "msgtype",
                    "msg_type",
                    options = listOf("text", "markdown"),
                    defaultValue = "text",
                ),
                ChannelFieldSpec("atAll", "at_all", options = BOOL, defaultValue = "false"),
                ChannelFieldSpec("atMobiles", "at_mobiles"),
                ChannelFieldSpec("atDingtalkIds", "at_dingtalk_ids"),
                ChannelFieldSpec("titleTemplate", "title_template"),
            ),
        ),
        ChannelTypeSpec(
            type = NotifyChannelType.WEWORK_ROBOT,
            fields = listOf(
                ChannelFieldSpec("webHook", "webhook", secret = true, required = true),
                ChannelFieldSpec(
                    "msgType",
                    "msg_type",
                    options = listOf("text", "markdown"),
                    defaultValue = "text",
                ),
                ChannelFieldSpec("atAll", "at_all", options = BOOL, defaultValue = "false"),
                ChannelFieldSpec("atUserIds", "at_user_ids"),
                ChannelFieldSpec("atMobiles", "at_mobiles"),
            ),
        ),
        ChannelTypeSpec(
            type = NotifyChannelType.FEISHU_ROBOT,
            fields = listOf(
                ChannelFieldSpec("webhook", "webhook", secret = true, required = true),
                ChannelFieldSpec("secret", "secret", secret = true),
                ChannelFieldSpec(
                    "msgType",
                    "msg_type",
                    options = listOf("interactive", "text"),
                    defaultValue = "interactive",
                ),
                ChannelFieldSpec("titleTemplate", "title_template"),
                ChannelFieldSpec("messageCard", "message_card", multiline = true),
                ChannelFieldSpec("atAll", "at_all", options = BOOL, defaultValue = "false"),
                ChannelFieldSpec("atOpenIds", "at_open_ids"),
            ),
        ),
        ChannelTypeSpec(
            type = NotifyChannelType.SERVERCHAN,
            fields = listOf(
                ChannelFieldSpec("sendKey", "send_key", secret = true, required = true),
                ChannelFieldSpec("channel", "channel"),
                ChannelFieldSpec("openid", "openid"),
                ChannelFieldSpec("titleTemplate", "title_template"),
            ),
        ),
        ChannelTypeSpec(
            type = NotifyChannelType.PUSHPLUS,
            fields = listOf(
                ChannelFieldSpec(
                    "website",
                    "website",
                    options = listOf("www.pushplus.plus", "pushplus.hxtrip.com"),
                    defaultValue = "www.pushplus.plus",
                ),
                ChannelFieldSpec("token", "token", secret = true, required = true),
                ChannelFieldSpec("topic", "topic"),
                ChannelFieldSpec(
                    "template",
                    "template",
                    options = listOf("html", "txt", "json", "markdown"),
                    defaultValue = "html",
                ),
                ChannelFieldSpec("channel", "channel"),
                ChannelFieldSpec("webhook", "webhook"),
                ChannelFieldSpec("callbackUrl", "callback_url"),
                ChannelFieldSpec("validTime", "valid_time"),
                ChannelFieldSpec("titleTemplate", "title_template"),
            ),
        ),
        ChannelTypeSpec(
            type = NotifyChannelType.GOTIFY,
            fields = listOf(
                ChannelFieldSpec("webServer", "server", secret = true, required = true),
                ChannelFieldSpec("token", "token", secret = true, required = true),
                ChannelFieldSpec("title", "title"),
                ChannelFieldSpec("priority", "priority"),
            ),
        ),
        // BARK / WEBHOOK 使用专用字段与手写 UI，这里只声明 config 中的敏感键，
        // 供持久化层加密；不参与动态渲染与默认值。
        ChannelTypeSpec(
            type = NotifyChannelType.BARK,
            fields = listOf(
                ChannelFieldSpec("key", "encryption_key", secret = true),
                ChannelFieldSpec("iv", "iv", secret = true),
            ),
        ),
        ChannelTypeSpec(
            type = NotifyChannelType.WEBHOOK,
            fields = listOf(
                ChannelFieldSpec("secret", "secret", secret = true),
                ChannelFieldSpec("response", "response"),
            ),
        ),
    ).associateBy { it.type }

    fun of(type: NotifyChannelType): ChannelTypeSpec? = specs[type]

    /** 新建渠道时写入的默认 config（只包含有默认值的字段）。 */
    fun defaults(type: NotifyChannelType): Map<String, String> {
        val spec = specs[type] ?: return emptyMap()
        return buildMap {
            spec.fields.forEach { field ->
                if (field.defaultValue.isNotEmpty()) put(field.key, field.defaultValue)
            }
        }
    }
}
