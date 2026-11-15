package com.benton.izukijs.mcp.resources

import org.json.JSONArray
import org.json.JSONObject

/** 一篇打包在 APK 内的开发文档（仓库 docs 目录下 Markdown 的镜像）。 */
data class McpDocEntry(
    /** 不带 `.md` 的文件名，如 `SCRIPT_API`；也用作资源 URI 末段。 */
    val id: String,
    val fileName: String,
    val title: String,
    val bytes: Int,
    val text: String,
) {
    val uri: String get() = "izuki://docs/$id"
}

/**
 * 开发文档目录：索引、全文与分页读取。
 *
 * 只依赖“列出资产名”和“读取资产内容”两个函数，不引用 `android.*`，便于 JVM 单测；
 * 具体来源由 [com.benton.izukijs.di.McpContainer] 用 `AssetManager` 注入。
 *
 * 文档正文只从 [documents] 的白名单里取，绝不把调用方传入的名字拼进资产路径，
 * 避免路径穿越。
 */
class McpDocCatalog(
    private val assetNames: () -> List<String>,
    private val assetReader: (String) -> String?,
) {

    @Volatile
    private var cached: List<McpDocEntry>? = null

    private val lock = Any()

    /** 全部文档，按文件名排序；读取失败或空资产的条目会被跳过。 */
    fun documents(): List<McpDocEntry> = cached ?: synchronized(lock) {
        cached ?: load().also { cached = it }
    }

    /** 按 id / 文件名查找，忽略大小写与 `.md` 后缀；找不到返回 null。 */
    fun find(name: String): McpDocEntry? {
        val id = normalizeName(name) ?: return null
        return documents().firstOrNull { it.id.equals(id, ignoreCase = true) }
    }

    fun indexJson(): JSONObject = JSONObject().apply {
        val docs = JSONArray()
        documents().forEach { entry ->
            docs.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.fileName)
                    .put("title", entry.title)
                    .put("uri", entry.uri)
                    .put("bytes", entry.bytes)
                    .put("chars", entry.text.length),
            )
        }
        put("ok", true)
        put("count", docs.length())
        put("docs", docs)
        put("note", NOTE)
    }

    /**
     * 读取一篇文档的一页；找不到返回 null。
     *
     * [offset] 为字符（UTF-16 码元）偏移，[maxChars] 会被收敛到
     * [MIN_MAX_CHARS]..[MAX_MAX_CHARS]。返回值带 `has_more` / `next_offset`，
     * 便于客户端在同一个 `docs` 工具里续读大文档。
     */
    fun read(name: String, offset: Int = 0, maxChars: Int = DEFAULT_MAX_CHARS): JSONObject? {
        val entry = find(name) ?: return null
        val text = entry.text
        val start = offset.coerceAtLeast(0)
        val limit = maxChars.coerceIn(MIN_MAX_CHARS, MAX_MAX_CHARS)
        val available = (text.length - start).coerceAtLeast(0)
        val end = if (available == 0) start else start + minOf(limit, available)
        val body = if (available == 0) "" else text.substring(start, end)
        val hasMore = end < text.length
        return JSONObject().apply {
            put("ok", true)
            put("id", entry.id)
            put("name", entry.fileName)
            put("title", entry.title)
            put("uri", entry.uri)
            put("bytes", entry.bytes)
            put("total_chars", text.length)
            put("offset", start)
            put("returned_chars", body.length)
            put("has_more", hasMore)
            if (hasMore) put("next_offset", end)
            put("text", body)
            put("note", NOTE)
        }
    }

    private fun load(): List<McpDocEntry> {
        val names = runCatching { assetNames() }.getOrNull().orEmpty()
        return names.asSequence()
            .filter { it.endsWith(".md", ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
            .mapNotNull { fileName ->
                val text = runCatching { assetReader(fileName) }.getOrNull() ?: return@mapNotNull null
                McpDocEntry(
                    id = fileName.dropLast(MD_SUFFIX.length),
                    fileName = fileName,
                    title = titleOf(text) ?: fileName.dropLast(MD_SUFFIX.length),
                    bytes = text.toByteArray(Charsets.UTF_8).size,
                    text = text,
                )
            }
            .toList()
    }

    /** 取首个一级标题作为标题；读取失败或没有标题时回退文件名。 */
    private fun titleOf(text: String): String? = text.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("# ") }
        ?.removePrefix("# ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    /** 规范化调用方传入的名字；含路径分隔符或 `..` 一律拒绝。 */
    private fun normalizeName(raw: String): String? {
        var name = raw.substringBefore('?').substringBefore('#').trim()
        if (name.endsWith(MD_SUFFIX, ignoreCase = true)) {
            name = name.dropLast(MD_SUFFIX.length).trim()
        }
        if (name.isEmpty()) return null
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        return name
    }

    companion object {
        const val MIME_MARKDOWN = "text/markdown"

        const val DEFAULT_MAX_CHARS = 8_000
        const val MIN_MAX_CHARS = 1_000
        const val MAX_MAX_CHARS = 40_000

        /** 所有入口都带上这句，避免模型把镜像文档当成当前版本的权威描述。 */
        const val NOTE = "文档为仓库镜像，可能滞后于当前版本；以实际行为与代码为准。"

        private const val MD_SUFFIX = ".md"
    }
}
