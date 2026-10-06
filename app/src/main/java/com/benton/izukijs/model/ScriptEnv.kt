package com.benton.izukijs.model

/**
 * 脚本运行参数声明。由脚本头部注释解析而来：
 *
 * ```js
 * // @env MODE=auto   运行模式
 * // @env TARGET=     目标包名
 * ```
 *
 * `KEY=默认值` 为必填，之后接两个及以上空格（或 ` # `）的内容作为说明文字。
 */
data class EnvField(
    val key: String,
    val default: String,
    val label: String,
)

/** 某个脚本持久化的输入参数与运行状态。 */
data class ScriptEnvData(
    /** 运行前写入的输入参数。 */
    val values: Map<String, String> = emptyMap(),
    /** 脚本通过 `state.set()` 写回的状态。 */
    val state: Map<String, String> = emptyMap(),
)

/** 一个脚本声明的全部运行参数。 */
data class ScriptEnvSpec(val fields: List<EnvField>) {

    val isEmpty: Boolean get() = fields.isEmpty()

    fun defaults(): Map<String, String> = fields.associate { it.key to it.default }

    companion object {
        private val LINE = Regex("""^\s*//\s*@env\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$""")
        private val GAP = Regex("""\s{2,}""")

        fun parse(source: String): ScriptEnvSpec {
            val fields = LinkedHashMap<String, EnvField>()
            source.lineSequence().forEach { line ->
                val match = LINE.find(line) ?: return@forEach
                val key = match.groupValues[1]
                val (default, label) = splitValueLabel(match.groupValues[2])
                fields[key] = EnvField(key = key, default = default, label = label)
            }
            return ScriptEnvSpec(fields.values.toList())
        }

        private fun splitValueLabel(raw: String): Pair<String, String> {
            val text = raw.trim()
            val gap = GAP.find(text)
            val hash = text.indexOf(" # ")
            return when {
                gap != null && (hash == -1 || gap.range.first < hash) ->
                    text.substring(0, gap.range.first).trim() to
                        text.substring(gap.range.last + 1).trim()
                hash != -1 -> text.substring(0, hash).trim() to text.substring(hash + 3).trim()
                else -> text to ""
            }
        }
    }
}
