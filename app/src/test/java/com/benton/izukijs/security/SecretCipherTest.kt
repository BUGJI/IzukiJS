package com.benton.izukijs.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * JVM 上 Android Keystore 与 `android.util.Base64` 不可用，SecretCipher 必须按文档降级：
 * 加密退回明文、无法解密的密文退回空串，且任何路径都不向调用方抛异常。
 */
class SecretCipherTest {

    @Test
    fun emptyValuesStayEmpty() {
        assertEquals("", SecretCipher.encrypt(""))
        assertEquals("", SecretCipher.decrypt(""))
    }

    @Test
    fun plainTextIsReturnedUnchanged() {
        assertEquals("already-plain", SecretCipher.decrypt("already-plain"))
    }

    @Test
    fun encryptionFallsBackToPlainTextWhenKeystoreUnavailable() {
        val encrypted = SecretCipher.encrypt("secret-value")

        assertEquals("secret-value", encrypted)
        assertFalse(encrypted.startsWith("v1:"))
    }

    @Test
    fun malformedCipherTextDecryptsToEmpty() {
        assertEquals("", SecretCipher.decrypt("v1:not-base64!!"))
        assertEquals("", SecretCipher.decrypt("v1:AAAA"))
    }
}
