package com.benton.izukijs.mcp.tools

/** 一条增量编辑：用 [newText] 替换精确匹配的 [oldText]。 */
internal data class ScriptEdit(
    val oldText: String,
    val newText: String,
    val replaceAll: Boolean = false,
)

internal data class ScriptEditOutcome(
    val index: Int,
    val matches: Int,
    val changed: Boolean,
    val line: Int,
)

internal data class ScriptEditResult(
    val content: String,
    val outcomes: List<ScriptEditOutcome>,
    val changed: Boolean,
) {
    val noopCount: Int get() = outcomes.count { !it.changed }
}

internal data class ScriptOccurrence(val line: Int, val preview: String)

/**
 * 增量编辑失败。[reason] 取值：`invalid` / `not_found` / `ambiguous`。
 * [occurrences] 用于让调用方直接看到候选行，便于自我纠正。
 */
internal class ScriptEditException(
    val reason: String,
    val editIndex: Int,
    message: String,
    val occurrences: List<ScriptOccurrence> = emptyList(),
) : Exception(message)

/**
 * 参考 AI Coding 工具的 search/replace 语义：
 * - 每条 `oldText` 必须在**当前**文本中精确匹配（含空白与换行）；
 * - 多条编辑按顺序应用，后一条看到前一条的结果；
 * - 全部在内存校验通过后才交给仓库落盘，任一失败不产生部分写入。
 */
internal object ScriptEditEngine {

    const val MAX_EDITS = 50
    private const val MAX_REPORTED_OCCURRENCES = 10
    private const val MIN_ANCHOR_PREFIX = 4

    fun apply(source: String, edits: List<ScriptEdit>): ScriptEditResult {
        if (edits.isEmpty()) {
            throw ScriptEditException("invalid", -1, "至少需要一条编辑")
        }
        if (edits.size > MAX_EDITS) {
            throw ScriptEditException("invalid", -1, "一次最多应用 $MAX_EDITS 条编辑")
        }
        var current = source
        val outcomes = ArrayList<ScriptEditOutcome>(edits.size)
        edits.forEachIndexed { index, edit ->
            if (edit.oldText.isEmpty()) {
                throw ScriptEditException(
                    "invalid",
                    index,
                    "第 ${index + 1} 条编辑的 oldText 不能为空",
                )
            }
            val occurrences = findOccurrences(current, edit.oldText)
            if (occurrences.isEmpty()) {
                val anchor = nearestAnchor(current, edit.oldText)
                val hint = anchor?.let { "；最接近的位置在第 ${it.line} 行：${it.preview}" } ?: ""
                throw ScriptEditException(
                    reason = "not_found",
                    editIndex = index,
                    message = "第 ${index + 1} 条编辑未匹配到 oldText$hint",
                    occurrences = listOfNotNull(anchor),
                )
            }
            if (occurrences.size > 1 && !edit.replaceAll) {
                throw ScriptEditException(
                    reason = "ambiguous",
                    editIndex = index,
                    message = "第 ${index + 1} 条编辑匹配到 ${occurrences.size} 处；" +
                        "请补充上下文，或设置 replaceAll=true",
                    occurrences = occurrences.take(MAX_REPORTED_OCCURRENCES)
                        .map { ScriptOccurrence(ScriptText.lineOf(current, it), ScriptText.previewAt(current, it)) },
                )
            }
            val firstLine = ScriptText.lineOf(current, occurrences.first())
            val changed = edit.newText != edit.oldText
            current = if (edit.replaceAll) {
                current.replace(edit.oldText, edit.newText)
            } else {
                val at = occurrences.first()
                current.substring(0, at) + edit.newText + current.substring(at + edit.oldText.length)
            }
            outcomes.add(
                ScriptEditOutcome(
                    index = index,
                    matches = occurrences.size,
                    changed = changed,
                    line = firstLine,
                ),
            )
        }
        return ScriptEditResult(current, outcomes, current != source)
    }

    private fun findOccurrences(text: String, needle: String): List<Int> {
        val result = ArrayList<Int>()
        var start = 0
        while (true) {
            val at = text.indexOf(needle, start)
            if (at < 0) break
            result.add(at)
            start = at + needle.length
        }
        return result
    }

    /**
     * 找不到时给出一个可能的目标行：取 oldText 首个非空行与每一行的最长公共前缀，
     * 前缀太短则不返回，避免把模型引到完全无关的位置。
     */
    private fun nearestAnchor(text: String, oldText: String): ScriptOccurrence? {
        val probe = oldText.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: return null
        if (probe.isEmpty()) return null
        var bestLine = 0
        var bestPrefix = 0
        text.lineSequence().forEachIndexed { index, line ->
            val score = commonPrefixLength(probe, line.trim())
            if (score > bestPrefix) {
                bestPrefix = score
                bestLine = index + 1
            }
        }
        if (bestLine == 0 || bestPrefix < MIN_ANCHOR_PREFIX) return null
        return ScriptOccurrence(bestLine, ScriptText.lineAt(text, bestLine).trim().take(120))
    }

    private fun commonPrefixLength(left: String, right: String): Int {
        val limit = minOf(left.length, right.length)
        var index = 0
        while (index < limit && left[index] == right[index]) index++
        return index
    }
}
