package com.benton.izukijs.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.benton.izukijs.BuildConfig
import com.benton.izukijs.core.fileProviderAuthority
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 安装结果：区分「已拉起系统安装器」与「缺少未知来源权限」，供 UI 给出不同引导。 */
sealed interface InstallResult {
    data object Started : InstallResult

    data object NeedPermission : InstallResult

    data class Failed(val failure: UpdateFailure) : InstallResult
}

/** 下载阶段的 HTTP 错误；保留状态码供管理器映射为可本地化的失败文案。 */
class UpdateHttpException(val code: Int) : IOException("HTTP $code")

object ApkInstaller {

    private const val MIME_APK = "application/vnd.android.package-archive"
    private const val UPDATE_DIR = "updates"

    fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val apkUrl = info.apkUrl ?: throw IOException("下载地址为空")
        val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
        cleanup(context)
        val target = File(dir, info.apkName?.takeIf { it.isNotBlank() } ?: "izukijs-update.apk")

        val connection = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "IzukiJS/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw UpdateHttpException(code)
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastPercent = Int.MIN_VALUE
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        copied += read
                        val percent = if (total > 0) ((copied * 100) / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        return target
    }

    fun install(context: Context, file: File): InstallResult {
        if (!file.exists() || file.length() <= 0L) {
            return InstallResult.Failed(UpdateFailure.MissingFile)
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            openInstallPermissionSettings(context)
            return InstallResult.NeedPermission
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
        }.getOrElse { return InstallResult.Failed(UpdateFailure.InstallFailed) }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_APK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            InstallResult.Started
        }.getOrElse { InstallResult.Failed(UpdateFailure.InstallFailed) }
    }

    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 清理下载目录；取消下载或安装包校验失败时调用。 */
    fun cleanup(context: Context) {
        File(context.cacheDir, UPDATE_DIR).listFiles()?.forEach { runCatching { it.delete() } }
    }

    fun openInBrowser(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
