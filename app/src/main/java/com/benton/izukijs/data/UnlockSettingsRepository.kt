package com.benton.izukijs.data

import android.content.Context
import android.content.SharedPreferences
import com.benton.izukijs.core.prefs.PrefsStateStore
import com.benton.izukijs.model.UnlockMethod
import com.benton.izukijs.model.UnlockPattern
import com.benton.izukijs.security.SecretCipher
import kotlinx.coroutines.flow.StateFlow

/**
 * 锁屏自主解锁设置。
 *
 * PIN 与图案均使用 [SecretCipher] 加密后存储；StateFlow 中只保留密文，避免界面、
 * 日志或备份意外拿到明文凭据。
 */
data class UnlockSettings(
    val enabled: Boolean = false,
    val method: UnlockMethod = UnlockMethod.SWIPE,
    val pinCipher: String = "",
    val patternCipher: String = "",
    val enterAfterPin: Boolean = true,
) {
    fun pin(): String = SecretCipher.decrypt(pinCipher)

    fun pattern(): UnlockPattern? = UnlockPattern.parseOrNull(SecretCipher.decrypt(patternCipher))

    val hasPin: Boolean get() = pinCipher.isNotEmpty()

    val hasPattern: Boolean get() = patternCipher.isNotEmpty()

    val hasCredential: Boolean
        get() = when (method) {
            UnlockMethod.SWIPE -> true
            UnlockMethod.PIN -> hasPin
            UnlockMethod.PATTERN -> hasPattern
        }
}

class UnlockSettingsRepository(context: Context) : PrefsStateStore<UnlockSettings>(
    context = context,
    prefsName = PREFS,
    defaultValue = UnlockSettings(),
) {

    val settings: StateFlow<UnlockSettings> get() = state

    fun setPin(plain: String) {
        save(current().copy(pinCipher = SecretCipher.encrypt(plain)))
    }

    fun setPattern(pattern: UnlockPattern?) {
        save(
            current().copy(
                patternCipher = pattern?.let { SecretCipher.encrypt(it.toJson()) }.orEmpty(),
            ),
        )
    }

    override fun read(): UnlockSettings = UnlockSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        method = UnlockMethod.fromStorage(prefs.getString(KEY_METHOD, null)),
        pinCipher = prefs.getString(KEY_PIN_CIPHER, "").orEmpty(),
        patternCipher = prefs.getString(KEY_PATTERN_CIPHER, "").orEmpty(),
        enterAfterPin = prefs.getBoolean(KEY_ENTER_AFTER_PIN, true),
    )

    override fun write(editor: SharedPreferences.Editor, value: UnlockSettings) {
        editor
            .putBoolean(KEY_ENABLED, value.enabled)
            .putString(KEY_METHOD, value.method.name)
            .putString(KEY_PIN_CIPHER, value.pinCipher)
            .putString(KEY_PATTERN_CIPHER, value.patternCipher)
            .putBoolean(KEY_ENTER_AFTER_PIN, value.enterAfterPin)
    }

    private companion object {
        const val PREFS = "izukijs_unlock"
        const val KEY_ENABLED = "enabled"
        const val KEY_METHOD = "method"
        const val KEY_PIN_CIPHER = "pin_cipher"
        const val KEY_PATTERN_CIPHER = "pattern_cipher"
        const val KEY_ENTER_AFTER_PIN = "enter_after_pin"
    }
}
