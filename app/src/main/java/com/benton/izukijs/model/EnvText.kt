package com.benton.izukijs.model

/** 环境变量文本框的解析结果。 */
sealed interface EnvParseResult {
    data class Success(val values: Map<String, String>) : EnvParseResult

    data class Failure(val lineNumber: Int, val content: String) : EnvParseResult
}

/**
 * 环境变量文本与运行时参数合并。
 *
 * 编辑框约定为每行 `KEY=VALUE`，空行与 `#` 注释会被忽略，值允许为空或包含 `=`。
 */
object EnvText {

    private val KEY = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

    fun parse(text: String): EnvParseResult {
        val values = LinkedHashMap<String, String>()
        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val separator = line.indexOf('=')
            if (separator <= 0) {
                return EnvParseResult.Failure(index + 1, rawLine)
            }
            val key = line.substring(0, separator).trim()
            if (key.isEmpty() || !KEY.matches(key)) {
                return EnvParseResult.Failure(index + 1, rawLine)
            }
            values[key] = line.substring(separator + 1).trim()
        }
        return EnvParseResult.Success(values)
    }

    fun format(values: Map<String, String>): String =
        values.entries
            .sortedBy { it.key }
            .joinToString("\n") { (key, value) -> "$key=$value" }

    /**
     * 按优先级合并：`@env` 默认值 < 全局变量（开关开启时）< 本脚本变量 < 本次运行输入。
     */
    fun resolve(
        defaults: Map<String, String>,
        global: Map<String, String>,
        script: Map<String, String>,
        runtime: Map<String, String>,
        useGlobal: Boolean,
    ): Map<String, String> = buildMap {
        putAll(defaults)
        if (useGlobal) putAll(global)
        putAll(script)
        putAll(runtime)
    }
}
