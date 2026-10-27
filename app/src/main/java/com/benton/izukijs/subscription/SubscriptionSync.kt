package com.benton.izukijs.subscription

import java.net.URL
import java.security.MessageDigest

object SubscriptionSync {

    const val MIN_INTERVAL_MINUTES = 15L
    const val DEFAULT_INTERVAL_MINUTES = 24L * 60L

    /** 订阅与扫码导入都只接受带主机的 http/https 链接。 */
    fun isHttpUrl(raw: String): Boolean = runCatching {
        val parsed = URL(raw.trim())
        val scheme = parsed.protocol.lowercase()
        (scheme == "http" || scheme == "https") && !parsed.host.isNullOrBlank()
    }.getOrDefault(false)

    fun normalizeInterval(minutes: Long?): Long {
        if (minutes == null) return DEFAULT_INTERVAL_MINUTES
        if (minutes <= 0L) return 0L
        return minutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
    }

    fun isDisabled(minutes: Long): Boolean = minutes <= 0L

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    fun shouldOverwrite(localContent: String, remoteContent: String, lastHash: String): Boolean {
        if (remoteContent == localContent) return false
        val remoteHash = sha256(remoteContent)
        if (lastHash.isNotBlank() && remoteHash == lastHash) return false
        return true
    }
}
