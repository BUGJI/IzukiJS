package com.benton.izukijs.ui.scripts

import android.content.Context
import android.net.Uri
import com.benton.izukijs.data.ScriptArchiveEntry
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** SAF 文本导出；调用方负责切到 IO 线程。 */
internal fun writeTextToUri(context: Context, uri: Uri, text: String): Boolean = runCatching {
    val stream = context.contentResolver.openOutputStream(uri) ?: return@runCatching false
    stream.use { it.write(text.toByteArray()) }
    true
}.getOrDefault(false)

/**
 * 把脚本 / 项目条目按原始字节打包为 zip。
 *
 * 项目可能包含图片等二进制资源，这里不能用文本内容构造条目；单个文件读取失败只跳过
 * 该条目，不中断整批导出。返回实际写入的条目数。
 */
internal fun writeZipToUri(
    context: Context,
    uri: Uri,
    entries: List<ScriptArchiveEntry>,
): Int = runCatching {
    val stream = context.contentResolver.openOutputStream(uri) ?: return@runCatching 0
    var written = 0
    ZipOutputStream(stream.buffered()).use { zip ->
        entries.forEach { entry ->
            val ok = runCatching {
                zip.putNextEntry(ZipEntry(entry.name))
                entry.file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                true
            }.getOrDefault(false)
            if (ok) {
                written++
            } else {
                runCatching { zip.closeEntry() }
            }
        }
    }
    written
}.getOrDefault(0)

/** SAF 文本导入；调用方负责切到 IO 线程。 */
internal fun readTextFromUri(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}.getOrNull()
