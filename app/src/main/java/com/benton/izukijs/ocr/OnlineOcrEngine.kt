package com.benton.izukijs.ocr

import android.graphics.Bitmap
import android.util.Base64
import com.benton.izukijs.runtime.LogBus
import com.benton.izukijs.service.CaptureSettings
import com.benton.izukijs.service.scaledByPercent
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 在线 OCR。按配置路由到百度 / Google Vision / 自定义服务，降低低端设备本地算力开销。
 */
class OnlineOcrEngine(
    private val config: OcrConfig,
    private val captureSettings: CaptureSettings,
    private val logBus: LogBus,
) {

    fun recognize(bitmap: Bitmap): OcrResult? {
        val scalePercent = captureSettings.scalePercent.coerceIn(10, 100)
        val scaled = bitmap.scaledByPercent(scalePercent)
        val result = try {
            val base64 = scaled.toBase64(captureSettings.jpegQuality)
            when (config.provider) {
                OcrProvider.BAIDU -> recognizeBaidu(base64)
                OcrProvider.GOOGLE_VISION -> recognizeGoogle(base64)
                OcrProvider.CUSTOM -> recognizeCustom(base64)
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        // 服务端返回的坐标基于缩放后的图片，换算回原始（屏幕）像素再交给调用方。
        return result?.rescale(100.0 / scalePercent)
    }

    private fun OcrResult.rescale(factor: Double): OcrResult {
        if (factor == 1.0) return this
        return copy(
            blocks = blocks.map { block ->
                block.copy(
                    x = (block.x * factor).toInt(),
                    y = (block.y * factor).toInt(),
                    width = (block.width * factor).toInt(),
                    height = (block.height * factor).toInt(),
                )
            },
        )
    }

    private fun Bitmap.toBase64(quality: Int): String {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    // ---- 百度 ----

    private fun recognizeBaidu(base64: String): OcrResult? {
        if (config.apiKey.isBlank() || config.secretKey.isBlank()) {
            logBus.warn("百度 OCR 未配置 API Key / Secret Key")
            return null
        }
        val token = fetchBaiduToken() ?: return null
        val url = "https://aip.baidubce.com/rest/2.0/ocr/v1/general_basic?access_token=$token"
        val body = "image=" + URLEncoder.encode(base64, "UTF-8")
        val response = httpPost(url, "application/x-www-form-urlencoded", body.toByteArray()) ?: return null

        val json = JSONObject(response)
        if (json.has("error_code")) {
            logBus.warn("百度 OCR 错误: ${json.optString("error_msg")}")
            return null
        }
        val words = json.optJSONArray("words_result") ?: return null
        val text = StringBuilder()
        val blocks = ArrayList<OcrBlock>()
        for (i in 0 until words.length()) {
            val item = words.getJSONObject(i)
            val word = item.optString("words")
            text.append(word).append('\n')
            val location = item.optJSONObject("location") ?: continue
            val left = location.optInt("left")
            val top = location.optInt("top")
            val width = location.optInt("width")
            val height = location.optInt("height")
            blocks.add(OcrBlock(word, left + width / 2, top + height / 2, width, height))
        }
        return OcrResult(text.toString().trim(), blocks)
    }

    private fun fetchBaiduToken(): String? {
        BaiduTokenCache.get(config.apiKey)?.let { return it }
        val url = "https://aip.baidubce.com/oauth/2.0/token" +
            "?grant_type=client_credentials" +
            "&client_id=${URLEncoder.encode(config.apiKey, "UTF-8")}" +
            "&client_secret=${URLEncoder.encode(config.secretKey, "UTF-8")}"
        val response = httpPost(url, "application/x-www-form-urlencoded", ByteArray(0)) ?: return null
        return runCatching {
            val json = JSONObject(response)
            val token = json.optString("access_token").takeIf { it.isNotBlank() } ?: return null
            val expiresIn = json.optLong("expires_in", DEFAULT_TOKEN_TTL_SECONDS)
            BaiduTokenCache.put(config.apiKey, token, expiresIn)
            token
        }.getOrNull()
    }

    // ---- Google Vision ----

    private fun recognizeGoogle(base64: String): OcrResult? {
        if (config.apiKey.isBlank()) {
            logBus.warn("Google Vision 未配置 API Key")
            return null
        }
        val url = "https://vision.googleapis.com/v1/images:annotate?key=${config.apiKey}"
        val body = JSONObject().apply {
            put(
                "requests",
                JSONArray().put(
                    JSONObject().apply {
                        put("image", JSONObject().put("content", base64))
                        put(
                            "features",
                            JSONArray().put(JSONObject().put("type", "TEXT_DETECTION")),
                        )
                    },
                ),
            )
        }.toString()
        val response = httpPost(url, "application/json; charset=utf-8", body.toByteArray()) ?: return null

        val responses = JSONObject(response).optJSONArray("responses") ?: return null
        if (responses.length() == 0) return null
        val first = responses.getJSONObject(0)
        if (first.has("error")) {
            logBus.warn("Google Vision 错误: ${first.optJSONObject("error")?.optString("message")}")
            return null
        }
        val annotations = first.optJSONArray("textAnnotations") ?: return null
        if (annotations.length() == 0) return null

        val fullText = annotations.getJSONObject(0).optString("description")
        val blocks = ArrayList<OcrBlock>()
        for (i in 1 until annotations.length()) {
            val annotation = annotations.getJSONObject(i)
            val vertices = annotation.optJSONObject("boundingPoly")?.optJSONArray("vertices") ?: continue
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            for (v in 0 until vertices.length()) {
                val x = vertices.getJSONObject(v).optInt("x")
                val y = vertices.getJSONObject(v).optInt("y")
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
            }
            if (minX == Int.MAX_VALUE) continue
            blocks.add(
                OcrBlock(
                    annotation.optString("description"),
                    (minX + maxX) / 2,
                    (minY + maxY) / 2,
                    maxX - minX,
                    maxY - minY,
                ),
            )
        }
        return OcrResult(fullText, blocks)
    }

    // ---- 自定义 ----

    private fun recognizeCustom(base64: String): OcrResult? {
        if (config.endpoint.isBlank()) {
            logBus.warn("自定义 OCR 未配置请求地址")
            return null
        }
        val body = JSONObject().put("image", base64).toString()
        val response = httpPost(
            config.endpoint,
            "application/json; charset=utf-8",
            body.toByteArray(),
        ) ?: return null
        val text = runCatching {
            val json = JSONObject(response)
            json.optString("text").ifBlank { json.optString("result") }
        }.getOrDefault(response)
        return OcrResult(text, emptyList())
    }

    // ---- HTTP ----

    private fun httpPost(url: String, contentType: String, body: ByteArray): String? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", contentType)
            if (config.headerName.isNotBlank()) {
                setRequestProperty(config.headerName, config.headerValue)
            }
        }
        connection.outputStream.use { it.write(body) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code in 200..299) {
            responseBody
        } else {
            logBus.warn("OCR HTTP $code: ${responseBody.take(200)}")
            null
        }
    } catch (t: Throwable) {
        logBus.error("在线 OCR 请求失败: ${t.message}")
        null
    }

    /** 进程内缓存百度 access_token，避免每次识别都发起一次额外的鉴权请求。 */
    private object BaiduTokenCache {
        @Volatile
        private var token: String? = null

        @Volatile
        private var apiKey: String? = null

        @Volatile
        private var expiresAtMillis: Long = 0L

        fun get(apiKey: String): String? =
            token?.takeIf { this.apiKey == apiKey && System.currentTimeMillis() < expiresAtMillis }

        fun put(apiKey: String, token: String, expiresInSeconds: Long) {
            this.token = token
            this.apiKey = apiKey
            expiresAtMillis = System.currentTimeMillis() +
                (expiresInSeconds.coerceAtLeast(60L) - 60L) * 1000L
        }
    }

    private companion object {
        const val DEFAULT_TOKEN_TTL_SECONDS = 2_592_000L
    }
}
