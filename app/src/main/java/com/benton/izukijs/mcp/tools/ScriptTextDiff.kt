package com.benton.izukijs.mcp.tools

/**
 * 有界的行级 unified diff，仅用于 `script_edit` 的预演与结果展示。
 *
 * 先在公共前后缀之外做 LCS；矩阵或输出过大时退化为行数摘要，
 * 避免大脚本把单线程设备队列卡在 diff 计算上。
 */
internal object ScriptTextDiff {

    private const val DEFAULT_CONTEXT = 3
    private const val MAX_MATRIX_CELLS = 1_000_000L
    private const val MAX_OUTPUT_LINES = 400
    private const val MAX_HUNKS = 50

    private enum class Kind { EQUAL, DELETE, INSERT }

    private data class Op(val kind: Kind, val text: String)

    fun unified(old: String, new: String, context: Int = DEFAULT_CONTEXT): String {
        if (old == new) return ""
        val before = splitLines(old)
        val after = splitLines(new)
        val ops = diff(before, after) ?: return summary(before, after)
        return render(ops, context) ?: summary(before, after)
    }

    private fun splitLines(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.split('\n')

    private fun summary(before: List<String>, after: List<String>): String =
        "（变更过大，已省略逐行 diff）旧 ${before.size} 行 → 新 ${after.size} 行"

    private fun diff(before: List<String>, after: List<String>): List<Op>? {
        val prefix = commonPrefix(before, after)
        val suffix = commonSuffix(before, after, prefix)
        val oldMiddle = before.subList(prefix, before.size - suffix)
        val newMiddle = after.subList(prefix, after.size - suffix)
        if (oldMiddle.size.toLong() * newMiddle.size.toLong() > MAX_MATRIX_CELLS) {
            return null
        }
        val ops = ArrayList<Op>(before.size + after.size)
        for (index in 0 until prefix) ops.add(Op(Kind.EQUAL, before[index]))
        ops.addAll(diffMiddle(oldMiddle, newMiddle))
        for (index in before.size - suffix until before.size) {
            ops.add(Op(Kind.EQUAL, before[index]))
        }
        return ops
    }

    private fun diffMiddle(before: List<String>, after: List<String>): List<Op> {
        val n = before.size
        val m = after.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (before[i] == after[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }
        val ops = ArrayList<Op>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                before[i] == after[j] -> {
                    ops.add(Op(Kind.EQUAL, before[i]))
                    i++
                    j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    ops.add(Op(Kind.DELETE, before[i]))
                    i++
                }
                else -> {
                    ops.add(Op(Kind.INSERT, after[j]))
                    j++
                }
            }
        }
        while (i < n) {
            ops.add(Op(Kind.DELETE, before[i]))
            i++
        }
        while (j < m) {
            ops.add(Op(Kind.INSERT, after[j]))
            j++
        }
        return ops
    }

    private fun render(ops: List<Op>, context: Int): String? {
        val changes = ops.indices.filter { ops[it].kind != Kind.EQUAL }
        if (changes.isEmpty()) return ""

        val oldLines = IntArray(ops.size + 1)
        val newLines = IntArray(ops.size + 1)
        for (index in ops.indices) {
            oldLines[index + 1] = oldLines[index] + if (ops[index].kind == Kind.INSERT) 0 else 1
            newLines[index + 1] = newLines[index] + if (ops[index].kind == Kind.DELETE) 0 else 1
        }

        val groups = ArrayList<IntRange>()
        var first = changes.first()
        var last = first
        for (index in changes.drop(1)) {
            if (index - last <= context * 2 + 1) {
                last = index
            } else {
                groups.add(first..last)
                first = index
                last = index
            }
        }
        groups.add(first..last)
        if (groups.size > MAX_HUNKS) return null

        val output = StringBuilder()
        var outputLines = 0
        for (group in groups) {
            val start = (group.first - context).coerceAtLeast(0)
            val end = (group.last + context + 1).coerceAtMost(ops.size)
            val oldStart = oldLines[start] + 1
            val newStart = newLines[start] + 1
            val oldCount = oldLines[end] - oldLines[start]
            val newCount = newLines[end] - newLines[start]
            output.append("@@ -$oldStart,$oldCount +$newStart,$newCount @@\n")
            outputLines++
            for (index in start until end) {
                val op = ops[index]
                val marker = when (op.kind) {
                    Kind.EQUAL -> ' '
                    Kind.DELETE -> '-'
                    Kind.INSERT -> '+'
                }
                output.append(marker).append(op.text).append('\n')
                outputLines++
                if (outputLines > MAX_OUTPUT_LINES) return null
            }
        }
        return output.toString().trimEnd('\n')
    }

    private fun commonPrefix(before: List<String>, after: List<String>): Int {
        val limit = minOf(before.size, after.size)
        var index = 0
        while (index < limit && before[index] == after[index]) index++
        return index
    }

    private fun commonSuffix(before: List<String>, after: List<String>, prefix: Int): Int {
        val limit = minOf(before.size, after.size) - prefix
        var index = 0
        while (index < limit &&
            before[before.size - 1 - index] == after[after.size - 1 - index]
        ) {
            index++
        }
        return index
    }
}
