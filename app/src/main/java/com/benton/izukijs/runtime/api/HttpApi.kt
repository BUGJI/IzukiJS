package com.benton.izukijs.runtime.api

import android.webkit.JavascriptInterface
import com.benton.izukijs.runtime.HttpSettings
import com.benton.izukijs.runtime.LogBus
import com.benton.izukijs.runtime.isHttpHostAllowed
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * 脚本网络 API。挂在全局 `http` 命名空间，默认关闭，可在「权限与能力 → 其他」启用。
 *
 * 只支持 http / https；域名白名单为空时放行任意主机，填写后按主机或子域匹配。
 * 请求方法、Header、请求体、超时与是否跟随重定向均由脚本指定。为避免脚本误读超大响应，
 * 单次响应体上限 [MAX_BODY_BYTES]，超出会截断并标记 `truncated`。
 */
class HttpApi(
    private val logBus: LogBus,
    private val settingsProvider: () -> HttpSettings,
) {

    @JavascriptInterface
    fun available(): Boolean = settingsProvider().enabled

    @JavascriptInterface
    fun isAllowed(url: String): Boolean {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        if (!isHttpScheme(parsed.protocol)) return false
        val settings = settingsProvider()
        return settings.enabled && isHttpHostAllowed(parsed.host, settings.allowlist)
    }

    /**
     * 发起一次请求，返回 JSON 字符串：
     * `{ok, status, headers, body, url, durationMs, truncated?, error?}`。
     */
    @JavascriptInterface
    fun requestRaw(optionsJson: String): String {
        val started = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        return try {
            val options = JSONObject(optionsJson)
            val rawUrl = options.optString("url").trim()
            if (rawUrl.isEmpty()) return error("缺少 url")

            val settings = settingsProvider()
            if (!settings.enabled) return error("脚本网络未启用，请在「权限与能力」中开启")

            val url = URL(rawUrl)
            if (!isHttpScheme(url.protocol)) return error("仅支持 http / https")
            if (!isHttpHostAllowed(url.host, settings.allowlist)) {
                return error("域名 ${url.host} 不在白名单中")
            }

            val method = options.optString("method", "GET").trim().uppercase()
            val timeout = options.optInt("timeoutMs", DEFAULT_TIMEOUT_MS)
                .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
            val body = if (method == "GET" || method == "HEAD") {
                null
            } else {
                options.optStringOrNull("body")
            }

            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeout
                readTimeout = timeout
                instanceFollowRedirects = options.optBoolean("followRedirects", true)
                useCaches = false
                options.optJSONObject("headers")?.let { headers ->
                    headers.keys().forEach { name ->
                        val value = headers.optString(name)
                        if (value.isNotEmpty()) setRequestProperty(name, value)
                    }
                }
            }

            if (body != null) {
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }

            val status = connection.responseCode
            val charset = connection.contentType?.let { charsetOf(it) } ?: StandardCharsets.UTF_8
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val bodyResult = stream?.let { readLimited(it, charset) } ?: LimitedBody("", false)
            if (stream != null) runCatching { stream.close() }

            val result = JSONObject().apply {
                put("ok", status in 200..299)
                put("status", status)
                put("headers", headersJson(connection))
                put("body", bodyResult.text)
                put("url", connection.url.toString())
                put("durationMs", System.currentTimeMillis() - started)
                if (bodyResult.truncated) put("truncated", true)
            }
            logBus.debug(
                "HTTP $method ${url.host} → $status " +
                    "(${result.optLong("durationMs")}ms, ${bodyResult.text.length} chars)",
            )
            result.toString()
        } catch (t: Throwable) {
            logBus.warn("HTTP 请求失败: ${t.message}")
            error(t.message ?: "请求失败", System.currentTimeMillis() - started)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun error(message: String, durationMs: Long = 0L): String = JSONObject().apply {
        put("ok", false)
        put("status", 0)
        put("headers", JSONObject())
        put("body", "")
        put("durationMs", durationMs)
        put("error", message)
    }.toString()

    private fun headersJson(connection: HttpURLConnection): JSONObject = JSONObject().apply {
        connection.headerFields.forEach { (name, values) ->
            if (name != null && values != null) put(name, values.joinToString(","))
        }
    }

    private data class LimitedBody(val text: String, val truncated: Boolean)

    private fun readLimited(stream: InputStream, charset: Charset): LimitedBody {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            val remaining = MAX_BODY_BYTES - total
            if (remaining > 0) {
                output.write(buffer, 0, minOf(read, remaining))
            }
            total += read
            if (total >= MAX_BODY_BYTES) {
                return LimitedBody(output.toString(charset.name()), true)
            }
        }
        return LimitedBody(output.toString(charset.name()), false)
    }

    private fun charsetOf(contentType: String): Charset = runCatching {
        val match = CHARSET_REGEX.find(contentType)
        if (match != null) Charset.forName(match.groupValues[1]) else StandardCharsets.UTF_8
    }.getOrDefault(StandardCharsets.UTF_8)

    private fun isHttpScheme(scheme: String): Boolean =
        scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000
        const val MIN_TIMEOUT_MS = 1_000
        const val MAX_TIMEOUT_MS = 60_000

        /** 单次响应体上限，避免脚本读入超大文件。 */
        const val MAX_BODY_BYTES = 2 * 1024 * 1024

        val CHARSET_REGEX = Regex("""charset\s*=\s*"?([^";\s]+)""", RegexOption.IGNORE_CASE)
    }
}
