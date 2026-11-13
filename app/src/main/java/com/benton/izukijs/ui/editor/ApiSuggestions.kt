package com.benton.izukijs.ui.editor

import com.benton.izukijs.runtime.api.ScriptApiCatalog

/** 一条 API 补全建议；[text] 是实际插入编辑器的文本。 */
data class ApiSuggestion(val text: String)

/**
 * 编辑器里的轻量 API 补全。不做 JavaScript 语法分析，只根据光标前的标识符 / 命名空间
 * 给出静态候选；这样无需引入额外依赖，也不会误改脚本。
 *
 * 候选清单来自 [ScriptApiCatalog]，与运行时注册、`@need` 门控共用同一份元数据。
 */
object ApiSuggestions {

    private val TOKEN = Regex("""[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z0-9_$]*)?$""")

    private val TOP_LEVEL: List<ApiSuggestion> =
        ScriptApiCatalog.namespaces.map { ApiSuggestion("${it.name}.") } +
            ScriptApiCatalog.globals.flatMap { entry -> entry.suggestions.map(::ApiSuggestion) }

    private val MEMBERS: Map<String, List<String>> =
        ScriptApiCatalog.namespaces.associate { it.name to it.suggestions }

    /**
     * 只回溯光标前有限窗口：标识符 / 命名空间不会很长，避免每次按键都复制并扫描整篇脚本。
     */
    private const val TOKEN_SCAN_WINDOW = 64

    private fun tokenWindow(text: String, cursor: Int): Pair<Int, String>? {
        if (cursor <= 0 || cursor > text.length) return null
        val start = (cursor - TOKEN_SCAN_WINDOW).coerceAtLeast(0)
        return start to text.substring(start, cursor)
    }

    /** 光标前最后一个标识符 / 命名空间，例如 `sel`、`selector.fi`、`app.`。 */
    fun tokenAt(text: String, cursor: Int): String? {
        val (_, window) = tokenWindow(text, cursor) ?: return null
        return TOKEN.find(window)?.value
    }

    /** 当前标识符的起始下标，用于替换补全。 */
    fun tokenStart(text: String, cursor: Int): Int {
        val (start, window) = tokenWindow(text, cursor) ?: return cursor
        val match = TOKEN.find(window) ?: return cursor
        return start + match.range.first
    }

    /** 当前标识符在光标之后的结束下标，用于把整个 token 一起替换掉。 */
    fun tokenEnd(text: String, cursor: Int): Int {
        if (cursor < 0 || cursor >= text.length) return cursor
        var end = cursor
        while (end < text.length && isTokenChar(text[end])) end++
        return end
    }

    private fun isTokenChar(char: Char): Boolean =
        char.isLetterOrDigit() || char == '_' || char == '$'

    fun suggestions(token: String, limit: Int = 6): List<ApiSuggestion> {
        if (token.isEmpty()) return emptyList()
        val dot = token.lastIndexOf('.')
        val result = if (dot >= 0) {
            val namespace = token.substring(0, dot)
            val prefix = token.substring(dot + 1)
            MEMBERS[namespace].orEmpty()
                .filter { it.startsWith(prefix, ignoreCase = true) }
                .distinct()
                .map { ApiSuggestion("$namespace.$it") }
        } else {
            TOP_LEVEL.filter { it.text.startsWith(token, ignoreCase = true) }
        }
        return result
            // 接受补全后光标停在 `(` 后，token 仍与刚插入的候选相同；过滤掉它，
            // 否则补全条看起来像“点了没反应”。
            .filter { it.text != token && it.text != "$token(" }
            .take(limit.coerceIn(1, 12))
    }
}
