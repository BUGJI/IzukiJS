package com.benton.izukijs.subscription

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest

object SubscriptionSync {

    const val MIN_INTERVAL_MINUTES = 15L
    const val DEFAULT_INTERVAL_MINUTES = 24L * 60L

    /** 单个订阅脚本的下载上限，防止异常链接或错误配置把整段内容读进内存。 */
    const val MAX_SCRIPT_BYTES = 1024 * 1024

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

    /**
     * 按块读取响应体，累计超过 [limit] 立刻中止。
     *
     * 不用 Content-Length 判断：它可能缺失、被压缩后失真，或因为 chunked 编码而不可知。
     */
    fun readScript(input: InputStream, limit: Int = MAX_SCRIPT_BYTES): String {
        require(limit > 0) { "上限必须为正数" }
        val buffer = ByteArray(8192)
        val output = ByteArrayOutputStream(minOf(limit, 32 * 1024))
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > limit) throw IOException("订阅脚本超过大小上限（$limit 字节）")
            output.write(buffer, 0, count)
        }
        return output.toString("UTF-8")
    }
}
