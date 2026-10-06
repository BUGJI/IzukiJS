package com.benton.izukijs.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * 轻量 JS 语法高亮。基于 VisualTransformation，不引入额外编辑器依赖。
 *
 * 采用「按行分词 + 状态感知缓存」：一行的结果只取决于行文本与其进入时的词法状态
 * （普通 / 块注释 / 模板字符串），因此编辑某一行时，其余未改动行可直接命中缓存，
 * 避免每次按键都对整篇文档重跑分词。
 */
class JsSyntaxHighlighter(private val palette: Palette = Palette.dark()) : VisualTransformation {

    data class Palette(
        val keyword: Color,
        val string: Color,
        val number: Color,
        val comment: Color,
        val api: Color,
    ) {
        companion object {
            fun dark() = Palette(
                keyword = Color(0xFFC586C0),
                string = Color(0xFFCE9178),
                number = Color(0xFFB5CEA8),
                comment = Color(0xFF6A9955),
                api = Color(0xFF9CDCFE),
            )

            fun light() = Palette(
                keyword = Color(0xFFAF00DB),
                string = Color(0xFFA31515),
                number = Color(0xFF098658),
                comment = Color(0xFF008000),
                api = Color(0xFF0070C1),
            )
        }
    }

    private enum class LexState { NORMAL, BLOCK_COMMENT, TEMPLATE }

    private data class Segment(val text: String, val color: Color?)

    private data class LineResult(val segments: List<Segment>, val exit: LexState)

    private var cachedInput: String? = null
    private var cachedOutput: TransformedText? = null

    // 以 (进入状态, 行文本) 为键的 LRU 缓存；编辑单行时其余行直接复用。
    private val lineCache = object : LinkedHashMap<Pair<LexState, String>, LineResult>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<LexState, String>, LineResult>?): Boolean =
            size > MAX_CACHED_LINES
    }

    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        cachedOutput?.let { if (cachedInput == source) return it }
        val result = highlight(source)
        cachedInput = source
        cachedOutput = result
        return result
    }

    private fun highlight(source: String): TransformedText {
        if (source.length > MAX_HIGHLIGHT_LENGTH) {
            return TransformedText(AnnotatedString(source), OffsetMapping.Identity)
        }
        val builder = AnnotatedString.Builder()
        var state = LexState.NORMAL
        var lineStart = 0
        val length = source.length
        while (lineStart <= length) {
            val newline = source.indexOf('\n', lineStart)
            val lineEnd = if (newline >= 0) newline else length
            val line = source.substring(lineStart, lineEnd)
            val result = lineCache.getOrPut(state to line) { tokenizeLine(line, state) }
            for (segment in result.segments) {
                val color = segment.color
                if (color == null) {
                    builder.append(segment.text)
                } else {
                    builder.pushStyle(SpanStyle(color = color))
                    builder.append(segment.text)
                    builder.pop()
                }
            }
            if (newline < 0) break
            builder.append("\n")
            state = result.exit
            lineStart = newline + 1
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    private fun tokenizeLine(line: String, entry: LexState): LineResult {
        val segments = ArrayList<Segment>()
        val plain = StringBuilder()
        val state = entry

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                segments.add(Segment(plain.toString(), null))
                plain.clear()
            }
        }

        fun styled(text: String, color: Color) {
            if (text.isEmpty()) return
            flushPlain()
            segments.add(Segment(text, color))
        }

        var i = 0
        if (state == LexState.BLOCK_COMMENT) {
            val end = line.indexOf("*/")
            if (end < 0) return LineResult(listOf(Segment(line, palette.comment)), LexState.BLOCK_COMMENT)
            styled(line.substring(0, end + 2), palette.comment)
            i = end + 2
        } else if (state == LexState.TEMPLATE) {
            val end = findUnescaped(line, '`', 0)
            if (end < 0) return LineResult(listOf(Segment(line, palette.string)), LexState.TEMPLATE)
            styled(line.substring(0, end + 1), palette.string)
            i = end + 1
        }

        while (i < line.length) {
            val c = line[i]
            when {
                c == '/' && i + 1 < line.length && line[i + 1] == '/' -> {
                    styled(line.substring(i), palette.comment)
                    i = line.length
                }

                c == '/' && i + 1 < line.length && line[i + 1] == '*' -> {
                    val end = line.indexOf("*/", i + 2)
                    if (end < 0) {
                        styled(line.substring(i), palette.comment)
                        return LineResult(segments, LexState.BLOCK_COMMENT)
                    }
                    styled(line.substring(i, end + 2), palette.comment)
                    i = end + 2
                }

                c == '"' || c == '\'' -> {
                    val end = findUnescaped(line, c, i + 1)
                    val stop = if (end < 0) line.length else end + 1
                    styled(line.substring(i, stop), palette.string)
                    i = stop
                }

                c == '`' -> {
                    val end = findUnescaped(line, '`', i + 1)
                    if (end < 0) {
                        styled(line.substring(i), palette.string)
                        return LineResult(segments, LexState.TEMPLATE)
                    }
                    styled(line.substring(i, end + 1), palette.string)
                    i = end + 1
                }

                c.isDigit() && (i == 0 || !line[i - 1].isIdentifierPart()) -> {
                    var j = i
                    while (j < line.length && (line[j].isDigit() || line[j] == '.')) j++
                    styled(line.substring(i, j), palette.number)
                    i = j
                }

                c.isIdentifierStart() -> {
                    var j = i
                    while (j < line.length && line[j].isIdentifierPart()) j++
                    val word = line.substring(i, j)
                    val color = when {
                        word in KEYWORD_SET -> palette.keyword
                        word in API_SET -> palette.api
                        else -> null
                    }
                    if (color == null) plain.append(word) else styled(word, color)
                    i = j
                }

                else -> {
                    plain.append(c)
                    i++
                }
            }
        }
        flushPlain()
        return LineResult(segments, LexState.NORMAL)
    }

    private fun findUnescaped(line: String, quote: Char, from: Int): Int {
        var i = from
        while (i < line.length) {
            when {
                line[i] == '\\' -> i += 2
                line[i] == quote -> return i
                else -> i++
            }
        }
        return -1
    }

    private fun Char.isIdentifierStart(): Boolean = this == '_' || this == '$' || isLetter()

    private fun Char.isIdentifierPart(): Boolean = isIdentifierStart() || isDigit()

    private companion object {
        /** 超长文档跳过高亮，避免每次输入都做全量分词。 */
        private const val MAX_HIGHLIGHT_LENGTH = 20_000

        private const val MAX_CACHED_LINES = 1024

        private const val KEYWORDS =
            "var|let|const|function|return|if|else|for|while|do|break|continue|new|delete|" +
                "typeof|instanceof|in|of|this|null|undefined|true|false|try|catch|finally|throw|" +
                "switch|case|default|class|extends|super|import|export|async|await|yield|void"

        private const val APIS =
            "toast|log|debug|warn|error|sleep|exit|captureScreen|click|longClick|swipe|press|" +
                "input|back|home|recents|device|app|selector|console|shell|images|ocr|permissions|require"

        private val KEYWORD_SET = KEYWORDS.split('|').toHashSet()
        private val API_SET = APIS.split('|').toHashSet()
    }
}
