package com.benton.izukijs.update

import android.os.Build
import com.benton.izukijs.BuildConfig
import com.benton.izukijs.runtime.LogBus
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

data class UpdateInfo(
    val versionName: String,
    val tagName: String,
    val releaseUrl: String,
    val changelog: String,
    val apkUrl: String?,
    val apkName: String?,
    val apkSizeBytes: Long,
)

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult

    data class Available(val info: UpdateInfo) : UpdateCheckResult

    data class Failure(val message: String) : UpdateCheckResult
}

class UpdateChecker(private val logBus: LogBus? = null) {

    fun check(): UpdateCheckResult {
        val body = try {
            httpGet(LATEST_RELEASE_API)
        } catch (t: Throwable) {
            logBus?.warn("检查更新失败: ${t.message}")
            return UpdateCheckResult.Failure(t.message ?: "网络错误")
        } ?: return UpdateCheckResult.Failure("请求失败")

        return try {
            val release = JSONObject(body)
            val tag = release.optString("tag_name").trim()
            if (tag.isBlank()) {
                return UpdateCheckResult.Failure(release.optString("message", "缺少版本信息"))
            }
            val version = tag.removePrefix("v").removePrefix("V")
            if (compareVersions(version, BuildConfig.VERSION_NAME) <= 0) {
                return UpdateCheckResult.UpToDate
            }
            val asset = pickAsset(release.optJSONArray("assets"))
            UpdateCheckResult.Available(
                UpdateInfo(
                    versionName = version,
                    tagName = tag,
                    releaseUrl = release.optString("html_url").ifBlank { RELEASES_PAGE },
                    changelog = release.optString("body").trim(),
                    apkUrl = asset?.optString("browser_download_url")?.takeIf { it.isNotBlank() },
                    apkName = asset?.optString("name")?.takeIf { it.isNotBlank() },
                    apkSizeBytes = asset?.optLong("size", 0L) ?: 0L,
                ),
            )
        } catch (t: Throwable) {
            logBus?.warn("解析更新信息失败: ${t.message}")
            UpdateCheckResult.Failure(t.message ?: "解析失败")
        }
    }

    private fun pickAsset(assets: JSONArray?): JSONObject? {
        if (assets == null) return null
        for (abi in Build.SUPPORTED_ABIS) {
            val target = "app-$abi-release.apk"
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name").equals(target, ignoreCase = true)) return asset
            }
        }
        return null
    }

    private fun httpGet(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "IzukiJS/${BuildConfig.VERSION_NAME}")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            if (code in 200..299) {
                text
            } else {
                logBus?.warn("检查更新 HTTP $code")
                text
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun compareVersions(a: String, b: String): Int {
        val left = normalize(a)
        val right = normalize(b)
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val x = left.getOrNull(i) ?: 0
            val y = right.getOrNull(i) ?: 0
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun normalize(version: String): List<Int> =
        version.substringBefore('-').substringBefore('+').split('.')
            .map { it.trim().toIntOrNull() ?: 0 }

    companion object {
        const val REPO = "BUGJI/IzukiJS"
        const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"
    }
}
