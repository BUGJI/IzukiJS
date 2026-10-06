package com.benton.izukijs.ocr

import android.content.Context
import com.benton.izukijs.security.SecretCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OCR 配置持久化（本地 / 在线路由 + 在线服务商配置）。
 */
class OcrConfigRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<OcrConfig> = _config.asStateFlow()

    fun current(): OcrConfig = _config.value

    fun save(value: OcrConfig) {
        _config.value = value
        prefs.edit()
            .putString(KEY_MODE, value.mode.name)
            .putString(KEY_PROVIDER, value.provider.name)
            .putString(KEY_API_KEY, SecretCipher.encrypt(value.apiKey))
            .putString(KEY_SECRET_KEY, SecretCipher.encrypt(value.secretKey))
            .putString(KEY_ENDPOINT, value.endpoint)
            .putString(KEY_HEADER_NAME, value.headerName)
            .putString(KEY_HEADER_VALUE, SecretCipher.encrypt(value.headerValue))
            .apply()
    }

    private fun load(): OcrConfig = OcrConfig(
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
