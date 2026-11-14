package com.benton.izukijs.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class NotifySigningTest {

    @Test
    fun hmacSha256MatchesKnownVector() {
        assertEquals(
            "97yD9DBThCSxMpjmqm+xQ+9NWaFJRhdZl0edvC0aPNg=",
            NotifySigning.hmacSha256Base64("key", "The quick brown fox jumps over the lazy dog"),
        )
    }

    @Test
    fun base64EncodesRawBytes() {
        assertEquals("AAEC", NotifySigning.base64(byteArrayOf(0, 1, 2)))
        assertEquals("", NotifySigning.base64(ByteArray(0)))
    }

    @Test
    fun urlEncodeHandlesSpacesAndUnicode() {
        assertEquals("a+b%26c", NotifySigning.urlEncode("a b&c"))
        assertEquals("%E4%B8%AD%E6%96%87", NotifySigning.urlEncode("中文"))
    }

    @Test
    fun md5HexMatchesKnownVector() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", NotifySigning.md5Hex("hello"))
    }

    @Test
    fun formBodySkipsEmptyValuesAndEncodesParts() {
        val body = NotifySigning.formBody(
            linkedMapOf(
                "a" to "1",
                "b" to "",
                "c" to "x y",
            ),
        )

        assertEquals("a=1&c=x+y", body)
        assertEquals("a+b=1", NotifySigning.formBody(mapOf("a b" to "1")))
        assertEquals("", NotifySigning.formBody(emptyMap()))
    }
}
