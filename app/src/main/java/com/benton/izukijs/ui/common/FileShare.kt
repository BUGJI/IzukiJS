package com.benton.izukijs.ui.common

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.benton.izukijs.core.fileProviderAuthority
import java.io.File

/** 在 cache/exports 下创建一个带时间戳的导出文件；目录不存在时自动创建。 */
fun newCacheExportFile(context: Context, prefix: String, extension: String): File {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    return File(dir, "${prefix}_${System.currentTimeMillis()}.$extension")
}

/**
 * 通过 FileProvider 分享缓存文件。分享失败时静默忽略，避免因设备没有匹配的
 * 分享目标而中断页面流程。
 */
fun shareFile(
    context: Context,
    file: File,
    chooserTitle: String,
    mimeType: String = "text/plain",
    subject: String? = null,
) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, chooserTitle)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
