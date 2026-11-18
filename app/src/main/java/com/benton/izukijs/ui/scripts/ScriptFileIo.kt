package com.benton.izukijs.ui.scripts

import android.content.Context
import android.net.Uri
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** SAF 文本导出；调用方负责切到 IO 线程。 */
internal fun writeTextToUri(context: Context, uri: Uri, text: String): Boolean = runCatching {
    val stream = context.contentResolver.openOutputStream(uri) ?: return@runCatching false
    stream.use { it.write(text.toByteArray()) }
    true
}.getOrDefault(false)

/** 把多个脚本打包为 zip；单个条目读取失败不会中断导出。 */
internal fun writeZipToUri(
    context: Context,
    uri: Uri,
    entries: List<Pair<String, String>>,
): Boolean = runCatching {
    val stream = context.contentResolver.openOutputStream(uri) ?: return@runCatching false
    ZipOutputStream(stream.buffered()).use { zip ->
        entries.forEach { (name, content) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
    }
    true
}.getOrDefault(false)

/** SAF 文本导入；调用方负责切到 IO 线程。 */
internal fun readTextFromUri(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}.getOrNull()
