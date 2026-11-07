package com.benton.izukijs.core.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL

/**
 * 脚本文本下载的通用工具。
 *
 * 与订阅业务无关（不依赖 [com.benton.izukijs.subscription] 的模型），
 * 因此放在 core.net，供 data 层与订阅层共用，避免包间循环依赖。
 */
object HttpScript {

    /** 单个脚本的下载上限，防止异常链接或错误配置把整段内容读进内存。 */
    const val MAX_SCRIPT_BYTES = 1024 * 1024

    /** 订阅与扫码导入都只接受带主机的 http/https 链接。 */
    fun isHttpUrl(raw: String): Boolean = runCatching {
        val parsed = URL(raw.trim())
        val scheme = parsed.protocol.lowercase()
        (scheme == "http" || scheme == "https") && !parsed.host.isNullOrBlank()
    }.getOrDefault(false)

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
