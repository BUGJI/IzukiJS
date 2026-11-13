package com.benton.izukijs.notification

import android.content.Context
import android.content.SharedPreferences
import com.benton.izukijs.core.notification.NotificationSettings
import com.benton.izukijs.core.notification.NotificationSettingsCodec
import com.benton.izukijs.core.notification.NotifyChannel
import com.benton.izukijs.core.notification.NotifyChannelSpecs
import com.benton.izukijs.core.prefs.PrefsStateStore
import com.benton.izukijs.security.SecretCipher
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * 通知系统配置持久化。
 *
 * 整份配置以 JSON 保存；渠道中的 URL / Header / 各类 token 在落盘前经 [SecretCipher]
 * 加密，读取时再解密。备份导出走内存中的明文模型（与 AI / OCR 配置一致），
 * 因此导出的备份 JSON 同样包含这些明文密钥。
 */
class NotificationSettingsRepository(context: Context) : PrefsStateStore<NotificationSettings>(
    context = context,
    prefsName = PREFS,
    defaultValue = NotificationSettings(),
) {

    val settings: StateFlow<NotificationSettings> get() = state

    override fun read(): NotificationSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return NotificationSettings().withDefaults()
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return NotificationSettings().withDefaults()
        return NotificationSettingsCodec.decode(json)
            .mapSecrets(SecretCipher::decrypt)
            .withDefaults()
    }

    override fun write(editor: SharedPreferences.Editor, value: NotificationSettings) {
        val encrypted = value.mapSecrets(SecretCipher::encrypt).withDefaults()
        editor.putString(KEY_SETTINGS, NotificationSettingsCodec.encode(encrypted).toString())
    }

    override fun normalize(value: NotificationSettings): NotificationSettings = value.withDefaults()

    private fun NotificationSettings.mapSecrets(transform: (String) -> String): NotificationSettings = copy(
        channels = channels.map { channel ->
            val spec = NotifyChannelSpecs.of(channel.type)
            val config = if (spec == null) {
                channel.config
            } else {
                channel.config.mapValues { (key, value) ->
                    if (key in spec.secretKeys) transform(value) else value
                }
            }
            channel.copy(
                url = transform(channel.url),
                headers = transform(channel.headers),
                deviceKey = transform(channel.deviceKey),
                botToken = transform(channel.botToken),
                token = transform(channel.token),
                config = config,
            )
        },
    )

    fun channel(id: String): NotifyChannel? = current().channels.firstOrNull { it.id == id }

    private companion object {
        const val PREFS = "izukijs_notification"
        const val KEY_SETTINGS = "settings"
    }
}
