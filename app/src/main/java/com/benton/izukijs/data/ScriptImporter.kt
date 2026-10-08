package com.benton.izukijs.data

import android.content.Context
import android.net.Uri
import com.benton.izukijs.runtime.LogBus
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * 扫码导入：识别图片中的二维码，读取其中的 http(s) 链接并下载为脚本。
 */
class ScriptImporter(
    private val context: Context,
    private val repository: ScriptRepository,
    private val logBus: LogBus,
) {

    sealed interface Result {
        /** 下载并保存成功，[name] 为最终（去重后）的脚本名。 */
        data class Success(val name: String) : Result

        /** 图片中没有可用的二维码。 */
        data object NoQrCode : Result

        /** 二维码内容不是合法的 http(s) 链接。 */
        data object InvalidLink : Result

        /** 链接有效但下载失败（网络错误 / 非 2xx / 超时）。 */
        data object DownloadFailed : Result
    }

    suspend fun importFromImage(uri: Uri): Result {
        val raw = decodeQrCode(uri)?.trim().orEmpty()
        if (raw.isEmpty()) return Result.NoQrCode
        if (!isSupportedUrl(raw)) return Result.InvalidLink
        val content = download(raw) ?: return Result.DownloadFailed
        val created = repository.createAsync(nameFromUrl(raw), content)
        return Result.Success(created.name)
    }

    private suspend fun decodeQrCode(uri: Uri): String? {
        val image = runCatching { InputImage.fromFilePath(context, uri) }.getOrNull() ?: return null
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
        return try {
            suspendCancellableCoroutine { continuation ->
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        if (continuation.isActive) {
                            continuation.resume(barcodes.firstNotNullOfOrNull { it.rawValue })
                        }
                    }
                    .addOnFailureListener { error ->
                        logBus.warn("二维码识别失败: ${error.message}")
                        if (continuation.isActive) continuation.resume(null)
                    }
            }
        } finally {
            scanner.close()
        }
    }

    private suspend fun download(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "text/javascript, application/javascript, text/plain, */*")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    logBus.warn("脚本下载失败 HTTP $code: $url")
                    null
                } else {
                    connection.inputStream.bufferedReader().use { it.readText() }
                        ?.takeIf { it.isNotBlank() }
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            logBus.warn("脚本下载失败: ${error.message}")
            null
        }
    }

    private fun isSupportedUrl(raw: String): Boolean = runCatching {
        val parsed = URL(raw)
        val scheme = parsed.protocol.lowercase()
        (scheme == "http" || scheme == "https") && !parsed.host.isNullOrBlank()
    }.getOrDefault(false)

    /** 从链接末尾推断脚本名，去掉扩展名与查询串；无法解析时退回 imported。 */
    private fun nameFromUrl(url: String): String {
        val path = runCatching { URL(url).path }.getOrDefault("")
        val fileName = path.substringAfterLast('/').substringBefore('?')
        return fileName.substringBeforeLast('.').ifBlank { fileName }.ifBlank { "imported" }
    }
}
