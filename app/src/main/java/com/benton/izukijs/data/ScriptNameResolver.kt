package com.benton.izukijs.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

/**
 * 解析导入脚本的名称。
 *
 * `OpenDocument` 返回的 `content://` URI 中 [Uri.getLastPathSegment] 是 DocumentsProvider 的
 * documentId，并非真实文件名（例如 MediaStore 系的 `msf:1000000123`、`image:42`）。
 * 因此优先查询 [OpenableColumns.DISPLAY_NAME]，取不到时再对 documentId 做保守回退。
 */
object ScriptNameResolver {

    private const val FALLBACK_NAME = "imported"

    private val STRIPPABLE_EXTENSIONS = setOf("js", "mjs", "txt", "json")

    fun fromDocument(displayName: String?, lastPathSegment: String?): String =
        normalize(displayName)
            ?: normalize(segmentCandidate(lastPathSegment))
            ?: FALLBACK_NAME

    /** 查询 provider 暴露的真实显示名；不支持或无权访问时返回 null。 */
    fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun segmentCandidate(segment: String?): String? {
        val raw = segment?.substringAfterLast('/')?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val afterColon = raw.substringAfterLast(':', missingDelimiterValue = "")
        if (afterColon.isNotEmpty()) {
            return afterColon.takeUnless { it.all(Char::isDigit) }
        }
        return raw.takeUnless { it.all(Char::isDigit) }
    }

    private fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val base = stripExtension(trimmed)
        return base.ifBlank { trimmed }.trim().ifBlank { null }
    }

    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name
        val extension = name.substring(dot + 1).lowercase()
        return if (extension in STRIPPABLE_EXTENSIONS) name.substring(0, dot) else name
    }
}
