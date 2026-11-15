package com.benton.izukijs.ui.virtualscreen

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.benton.izukijs.core.fileProviderAuthority
import com.benton.izukijs.ui.common.newCacheExportFile
import java.io.FileOutputStream

/**
 * 保存当前虚拟屏帧。
 *
 * Android 10+ 优先写入系统相册；旧系统或写入失败时回退到 `cache/exports`，
 * 由 FileProvider 提供可分享的 URI。函数内部负责回收 [bitmap]。
 */
fun saveVirtualScreenFrame(context: Context, bitmap: Bitmap): Uri? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveToGallery(context, bitmap) ?: saveToCache(context, bitmap)
    } else {
        saveToCache(context, bitmap)
    }
} finally {
    if (!bitmap.isRecycled) bitmap.recycle()
}

private fun saveToGallery(context: Context, bitmap: Bitmap): Uri? {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(
            MediaStore.Images.Media.DISPLAY_NAME,
            "izuki_virtual_${System.currentTimeMillis()}.png",
        )
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(
            MediaStore.Images.Media.RELATIVE_PATH,
            "${Environment.DIRECTORY_PICTURES}/Izuki",
        )
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = runCatching {
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }.getOrNull() ?: return null
    val written = runCatching {
        val output = resolver.openOutputStream(uri) ?: error("openOutputStream returned null")
        output.use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) error("compress failed") }
    }.isSuccess
    if (!written) {
        runCatching { resolver.delete(uri, null, null) }
        return null
    }
    runCatching {
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
    }
    return uri
}

private fun saveToCache(context: Context, bitmap: Bitmap): Uri? = runCatching {
    val file = newCacheExportFile(context, "virtual_screen", "png")
    val written = FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    if (!written) {
        file.delete()
        return@runCatching null
    }
    FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
}.getOrNull()
