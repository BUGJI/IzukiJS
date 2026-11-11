package com.benton.izukijs.notification

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** 极简 HTTP 传输层，复用脚本网络使用的 [HttpURLConnection]，避免新增依赖。 */
internal object HttpTransport {

    data class Result(val status: Int, val body: String) {
        val ok: Boolean get() = status in 200..299
    }

    fun request(
        url: String,
        method: String = "POST",
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        timeoutMs: Int = 10_000,
    ): Result {
        val parsed = URL(url.trim())
        require(parsed.protocol == "http" || parsed.protocol == "https") {
            "only http / https is supported"
        }
        var connection: HttpURLConnection? = null
        return try {
            connection = (parsed.openConnection() as HttpURLConnection).apply {
                requestMethod = method.trim().uppercase().ifEmpty { "POST" }
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                useCaches = false
                contentType?.let { setRequestProperty("Content-Type", it) }
                headers.forEach { (name, value) ->
                    if (name.isNotBlank() && value.isNotEmpty()) setRequestProperty(name, value)
                }
            }
            if (body != null && connection.requestMethod != "GET" && connection.requestMethod != "HEAD") {
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.let(::readLimited).orEmpty()
            if (stream != null) runCatching { stream.close() }
            Result(status, text)
        } catch (t: Throwable) {
            throw NotifySendException(t.message ?: t.javaClass.simpleName, t)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** 解析 `Name: Value` 行，忽略空行与 # 注释。 */
    fun parseHeaders(raw: String): Map<String, String> = buildMap {
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val index = trimmed.indexOf(':')
            if (index <= 0) return@forEach
            val name = trimmed.substring(0, index).trim()
            val value = trimmed.substring(index + 1).trim()
            if (name.isNotEmpty() && value.isNotEmpty()) put(name, value)
        }
    }

    private fun readLimited(stream: InputStream, limit: Int = 16 * 1024): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4 * 1024)
        var total = 0
        while (total < limit) {
            val count = stream.read(buffer)
            if (count <= 0) break
            val allowed = minOf(count, limit - total)
            output.write(buffer, 0, allowed)
            total += allowed
            if (allowed < count) break
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }
}

/** 渠道发送失败。消息会被写进日志与脚本返回值，不应包含密钥。 */
class NotifySendException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
