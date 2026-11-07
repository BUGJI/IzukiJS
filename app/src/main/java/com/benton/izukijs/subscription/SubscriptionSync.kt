package com.benton.izukijs.subscription

import com.benton.izukijs.core.net.HttpScript
import java.io.InputStream
import java.security.MessageDigest

object SubscriptionSync {

    const val MIN_INTERVAL_MINUTES = 15L
    const val DEFAULT_INTERVAL_MINUTES = 24L * 60L

    /** 单个订阅脚本的下载上限，防止异常链接或错误配置把整段内容读进内存。 */
    const val MAX_SCRIPT_BYTES = HttpScript.MAX_SCRIPT_BYTES

    /** 订阅与扫码导入都只接受带主机的 http/https 链接。 */
    fun isHttpUrl(raw: String): Boolean = HttpScript.isHttpUrl(raw)

    fun normalizeInterval(minutes: Long?): Long {
        if (minutes == null) return DEFAULT_INTERVAL_MINUTES
        if (minutes <= 0L) return 0L
        return minutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
    }

    fun isDisabled(minutes: Long): Boolean = minutes <= 0L

    /**
     * 自动任务只在订阅仍然启用、周期未被设为手动、且已到期时才执行。
     * [automatic] 为 false 表示用户显式点击同步，不受这些条件限制。
     */
    fun canSync(subscription: ScriptSubscription, automatic: Boolean, now: Long): Boolean =
        !automatic || (subscription.enabled && !isDisabled(subscription.intervalMinutes) &&
            subscription.nextSyncAtMillis <= now)

    /**
     * 设置页传上来的可能是排队同步之前的旧快照。同一 URL 时沿用仓库里的同步元数据，
     * 否则会把 contentHash / etag / lastSyncAtMillis 清空，导致下一次同步误判远端已变。
     */
    fun applySettings(incoming: ScriptSubscription, current: ScriptSubscription?): ScriptSubscription {
        val cleaned = incoming.copy(url = incoming.url.trim())
        if (current == null || current.url.trim() != cleaned.url) {
            return cleaned.copy(lastSyncAtMillis = 0L, lastError = "", contentHash = "", etag = "", lastModified = "")
        }
        return cleaned.copy(lastSyncAtMillis = current.lastSyncAtMillis, lastError = current.lastError,
            contentHash = current.contentHash, etag = current.etag, lastModified = current.lastModified)
    }

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
    fun readScript(input: InputStream, limit: Int = MAX_SCRIPT_BYTES): String =
        HttpScript.readScript(input, limit)
}
