package com.benton.izukijs.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * 在语法高亮之上叠加「查找匹配」的高亮背景。
 *
 * [JsSyntaxHighlighter] 使用 [androidx.compose.ui.text.input.OffsetMapping.Identity] 且不改动文本内容，
 * 因此匹配区间可直接作用在高亮结果上，无需做偏移换算。
 */
class MatchHighlightTransformation(
    private val base: JsSyntaxHighlighter,
    private val query: String,
    private val matchColor: Color,
    private val activeMatchColor: Color,
    private val activeRange: IntRange?,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val transformed = base.filter(text)
        if (query.isEmpty()) return transformed
        val highlighted = transformed.text
        val matches = findAllMatches(highlighted.text, query)
        if (matches.isEmpty()) return transformed

        val builder = AnnotatedString.Builder()
        builder.append(highlighted)
        matches.forEach { range ->
            val isActive = activeRange != null && range.first == activeRange.first
            builder.addStyle(
                SpanStyle(background = if (isActive) activeMatchColor else matchColor),
                range.first,
                range.last + 1,
            )
        }
        return TransformedText(builder.toAnnotatedString(), transformed.offsetMapping)
    }
}

/** 查找 [query] 在 [text] 中所有不重叠的出现位置（区分大小写）。 */
internal fun findAllMatches(text: String, query: String): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val result = ArrayList<IntRange>()
    var index = text.indexOf(query)
    while (index >= 0) {
        result.add(index until index + query.length)
        index = text.indexOf(query, index + query.length)
    }
    return result
}
