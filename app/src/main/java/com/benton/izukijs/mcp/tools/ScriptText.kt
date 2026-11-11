package com.benton.izukijs.mcp.tools

/**
 * 脚本文本的行级工具。
 *
 * 所有操作都基于原始字符串的下标，保证 CRLF、末尾换行等细节不被改写；
 * 分页返回的内容可以直接作为 `script_edit.oldText` 使用。
 */
internal object ScriptText {

    data class Slice(
        val content: String,
        val offset: Int,
        val count: Int,
        val total: Int,
        val truncated: Boolean,
        val nextOffset: Int,
    )

    /** 空文本为 0 行；末尾换行会多计一个空行，与 `String.lines()` 一致。 */
    fun lineCount(text: String): Int =
        if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

    /** 每一行起始下标；文本为空时返回 `[0]`。 */
    fun lineStarts(text: String): IntArray {
        val starts = ArrayList<Int>()
        starts.add(0)
        for (index in text.indices) {
            if (text[index] == '\n') starts.add(index + 1)
        }
        return starts.toIntArray()
    }

    /** 下标所在行号（1 基）。 */
    fun lineOf(text: String, index: Int): Int {
        if (index <= 0) return 1
        val end = index.coerceAtMost(text.length)
        var line = 1
        for (i in 0 until end) {
            if (text[i] == '\n') line++
        }
        return line
    }

    /**
     * 按行分页。[limit] <= 0 表示取剩余全部；[offset] 越界时返回空切片。
     * 返回的 [Slice.content] 是原始子串，保留原有换行符。
     */
    fun slice(text: String, offset: Int, limit: Int): Slice {
        val total = lineCount(text)
        if (total == 0) return Slice("", 0, 0, 0, false, 0)
        val starts = lineStarts(text)
        val start = offset.coerceIn(0, total)
        val end = if (limit <= 0) total else (start + limit).coerceAtMost(total)
        val startIndex = if (start < total) starts[start] else text.length
        val endIndex = if (end < total) starts[end] else text.length
        return Slice(
            content = text.substring(startIndex, endIndex),
            offset = start,
            count = end - start,
            total = total,
            truncated = end < total,
            nextOffset = end,
        )
    }

    /** 给 [text] 每行加上行号前缀，仅供阅读；不要把前缀复制进 `oldText`。 */
    fun numberLines(text: String, startLine: Int): String {
        if (text.isEmpty()) return ""
        val raw = text.split('\n').toMutableList()
        if (text.endsWith('\n') && raw.isNotEmpty()) raw.removeAt(raw.lastIndex)
        val width = (startLine + raw.size - 1).toString().length.coerceAtLeast(1)
        return raw.mapIndexed { index, line ->
            val number = (startLine + index).toString().padStart(width)
            "$number | ${line.removeSuffix("\r")}"
        }.joinToString("\n")
    }

    /** 读取 1 基行号对应的整行文本（不含行尾换行）。 */
    fun lineAt(text: String, line: Int): String {
        if (line <= 0) return ""
        val starts = lineStarts(text)
        if (line > starts.size) return ""
        val start = starts[line - 1]
        val end = if (line < starts.size) starts[line] - 1 else text.length
        return text.substring(start, end.coerceAtLeast(start)).removeSuffix("\r")
    }

    /** 下标附近的一行预览，超长时截断。 */
    fun previewAt(text: String, index: Int, maxChars: Int = 120): String {
        val safe = index.coerceIn(0, text.length)
        var start = safe
        while (start > 0 && text[start - 1] != '\n') start--
        var end = safe
        while (end < text.length && text[end] != '\n') end++
        val line = text.substring(start, end).removeSuffix("\r").trim()
        return if (line.length <= maxChars) line else line.take(maxChars) + "…"
    }
}
