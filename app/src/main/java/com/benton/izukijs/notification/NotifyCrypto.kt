package com.benton.izukijs.notification

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Bark 等渠道的 AES 加解密；算法名与 SmsForwarder 的取值兼容。 */
internal object NotifyCrypto {

    private val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val random = SecureRandom()

    fun randomAlphaNum(length: Int): String = buildString {
        repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    fun normalize(raw: String): String = raw.trim()
        .replace("AES128", "AES")
        .replace("AES192", "AES")
        .replace("AES256", "AES")
        .replace("PKCS7Padding", "PKCS5Padding")

    fun encryptBase64(plain: String, transformation: String, key: String, iv: String): String {
        val cipher = cipherFor(Cipher.ENCRYPT_MODE, transformation, key, iv)
        return Base64.getEncoder()
            .encodeToString(cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8)))
    }

    fun decryptBase64(cipherText: String, transformation: String, key: String, iv: String): String {
        val cipher = cipherFor(Cipher.DECRYPT_MODE, transformation, key, iv)
        val bytes = Base64.getDecoder().decode(cipherText)
        return String(cipher.doFinal(bytes), StandardCharsets.UTF_8)
    }

    private fun cipherFor(mode: Int, transformation: String, key: String, iv: String): Cipher {
        val normalized = normalize(transformation)
        val cipher = Cipher.getInstance(normalized)
        val keySpec = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES")
        val ivBytes = iv.toByteArray(StandardCharsets.UTF_8)
        when {
            normalized.contains("ECB", ignoreCase = true) ->
                cipher.init(mode, keySpec)

            normalized.contains("GCM", ignoreCase = true) ->
                cipher.init(mode, keySpec, GCMParameterSpec(128, ivBytes))

            normalized.contains("CBC", ignoreCase = true) ->
                cipher.init(mode, keySpec, IvParameterSpec(ivBytes))

            else -> throw IllegalArgumentException("unsupported transformation: $transformation")
        }
        return cipher
    }
}
