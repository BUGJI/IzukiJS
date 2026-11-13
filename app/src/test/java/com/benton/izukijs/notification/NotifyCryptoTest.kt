package com.benton.izukijs.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyCryptoTest {

    private val plain = "{\"title\":\"标题\",\"body\":\"验证码 123456\"}"

    @Test
    fun aesCbcRoundTrip() {
        val key = "0123456789abcdef"
        val iv = "abcdef0123456789"
        val encrypted = NotifyCrypto.encryptBase64(
            plain,
            "AES128/CBC/PKCS7Padding",
            key,
            iv,
        )
        assertTrue(encrypted.isNotBlank())
        assertEquals(
            plain,
            NotifyCrypto.decryptBase64(
                encrypted,
                "AES128/CBC/PKCS7Padding",
                key,
                iv,
            ),
        )
    }

    @Test
    fun aesGcmRoundTrip() {
        val key = "0123456789abcdef0123456789abcdef"
        val iv = "abcdef012345"
        val encrypted = NotifyCrypto.encryptBase64(
            plain,
            "AES256/GCM/NoPadding",
            key,
            iv,
        )
        assertEquals(
            plain,
            NotifyCrypto.decryptBase64(
                encrypted,
                "AES256/GCM/NoPadding",
                key,
                iv,
            ),
        )
    }

    @Test
    fun normalizeMapsPkcs7AndKeySize() {
        assertEquals("AES/CBC/PKCS5Padding", NotifyCrypto.normalize("AES128/CBC/PKCS7Padding"))
        assertEquals("AES/GCM/NoPadding", NotifyCrypto.normalize("AES256/GCM/NoPadding"))
    }

    @Test
    fun randomAlphaNumHasRequestedLength() {
        val value = NotifyCrypto.randomAlphaNum(12)
        assertEquals(12, value.length)
        assertTrue(value.all { it.isLetterOrDigit() })
    }
}
