package com.benton.izukijs.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 使用 Android Keystore 中的 AES-GCM 密钥对敏感字符串做本地加密存储。
 *
 * 密文格式为 `v1:<base64(iv + ciphertext)>`。Keystore 不可用或解密失败时会降级为
 * 明文（[encrypt]）或空串（[decrypt]），保证功能不因个别设备异常而中断。
 */
object SecretCipher {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "izuki_secret_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128
    private const val PREFIX = "v1:"

    @Volatile
    private var cachedKey: SecretKey? = null

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
            PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        }.getOrDefault(plain)
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val combined = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, combined, 0, IV_LENGTH),
            )
            String(cipher.doFinal(combined, IV_LENGTH, combined.size - IV_LENGTH), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getKey(): SecretKey =
        cachedKey ?: synchronized(this) {
            cachedKey ?: loadOrCreateKey().also { cachedKey = it }
        }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
