package com.benton.izukijs.ocr

import android.content.Context
import android.content.SharedPreferences
import com.benton.izukijs.core.prefs.PrefsStateStore
import com.benton.izukijs.security.SecretCipher
import kotlinx.coroutines.flow.StateFlow

/**
 * OCR 配置持久化（本地 / 在线路由 + 在线服务商配置）。
 */
class OcrConfigRepository(context: Context) : PrefsStateStore<OcrConfig>(
    context = context,
    prefsName = PREFS,
    defaultValue = OcrConfig(),
) {

    val config: StateFlow<OcrConfig> get() = state

    override fun read(): OcrConfig = OcrConfig(
        mode = runCatching { OcrMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(OcrMode.LOCAL),
        provider = runCatching { OcrProvider.valueOf(prefs.getString(KEY_PROVIDER, null) ?: "") }
            .getOrDefault(OcrProvider.BAIDU),
        apiKey = SecretCipher.decrypt(prefs.getString(KEY_API_KEY, "").orEmpty()),
        secretKey = SecretCipher.decrypt(prefs.getString(KEY_SECRET_KEY, "").orEmpty()),
        endpoint = prefs.getString(KEY_ENDPOINT, "").orEmpty(),
        headerName = prefs.getString(KEY_HEADER_NAME, "").orEmpty(),
        headerValue = SecretCipher.decrypt(prefs.getString(KEY_HEADER_VALUE, "").orEmpty()),
    )

    override fun write(editor: SharedPreferences.Editor, value: OcrConfig) {
        editor
            .putString(KEY_MODE, value.mode.name)
            .putString(KEY_PROVIDER, value.provider.name)
            .putString(KEY_API_KEY, SecretCipher.encrypt(value.apiKey))
            .putString(KEY_SECRET_KEY, SecretCipher.encrypt(value.secretKey))
            .putString(KEY_ENDPOINT, value.endpoint)
            .putString(KEY_HEADER_NAME, value.headerName)
            .putString(KEY_HEADER_VALUE, SecretCipher.encrypt(value.headerValue))
    }

    private companion object {
        const val PREFS = "izukijs_ocr"
        const val KEY_MODE = "mode"
        const val KEY_PROVIDER = "provider"
        const val KEY_API_KEY = "api_key"
        const val KEY_SECRET_KEY = "secret_key"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_HEADER_NAME = "header_name"
        const val KEY_HEADER_VALUE = "header_value"
    }
}
