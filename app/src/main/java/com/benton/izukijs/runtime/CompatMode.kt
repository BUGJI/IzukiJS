package com.benton.izukijs.runtime

/**
 * 旧脚本兼容模式的识别与静态分析。
 *
 * 只解析脚本头部的 `// @compat autojs6` 与开头的 Auto.js 执行指令，
 * 不做代码改写；运行时是否真正启用兼容层由 [JsEngine] 决定。
 * 这里保持纯 Kotlin，便于 JVM 单测与编辑器侧复用。
 */
object CompatMode {

    /** 推荐写入脚本首行的兼容标记。 */
    const val HEADER = "// @compat autojs6"

    private const val SCAN_LIMIT = 8192

    private val HEADER_PATTERN =
        Regex("""(?m)^[ \t]*//[ \t]*@compat[ \t]+([A-Za-z0-9_-]+).*$""")

    /** AutoX / AutoJs6 的 v6 API 家族共享同一套语义，统一归一到 autojs6。 */
    private val SUPPORTED_FLAVORS = mapOf(
        "autojs6" to "autojs6",
        "autojs" to "autojs6",
        "autox" to "autojs6",
    )

    private val KNOWN_DIRECTIVES = setOf(
        "ui", "ui-thread", "auto",
        "nodejs", "node", "bun", "quickjs", "qjs",
        "jsox", "x",
    )

    private val UNSUPPORTED_DIRECTIVES = setOf(
        "ui", "ui-thread",
        "nodejs", "node", "bun", "quickjs", "qjs",
    )

    /** 运行时无法降级、必须在启动前拒绝的能力（与 CLI 的阻断项保持一致）。 */
    private val BLOCKING_SIGNALS = mapOf(
        "java-interop" to ("unsupported-java-interop" to "Java 互操作"),
        "legacy-e4x" to ("unsupported-e4x" to "E4X XML"),
        "legacy-threads" to ("unsupported-threads" to "threads / engines"),
        "legacy-ui" to ("unsupported-ui-xml" to "ui / floaty XML"),
    )

    private val BLOCKING_ISSUE_CODES = BLOCKING_SIGNALS.values.map { it.first }.toSet()

    data class Issue(val code: String, val message: String)

    data class Signal(val code: String, val label: String)

    data class Analysis(
        val enabled: Boolean,
        val flavor: String?,
        val directives: Set<String>,
        val issues: List<Issue>,
        val signals: List<Signal>,
    ) {
        val unsupportedDirectives: Set<String>
            get() = directives.filterTo(LinkedHashSet()) { it in UNSUPPORTED_DIRECTIVES }

        /** 供运行时直接展示的“不支持”说明；没有不支持项时返回 null。 */
        fun unsupportedMessage(): String? {
            val parts = unsupportedDirectives.map { directive ->
                when (directive) {
                    "ui", "ui-thread" -> "\"ui\"; UI 模式"
                    else -> "\"$directive\"; 引擎指令"
                }
            }.toMutableList()
            issues.filter { it.code in BLOCKING_ISSUE_CODES }.forEach { parts += it.message }
            if (parts.isEmpty()) return null
            return "兼容层暂不支持${parts.joinToString("、")}；" +
                "请改用无 UI 的脚本，或先做人工移植"
        }
    }

    fun analyze(source: String): Analysis {
        val header = findHeader(source)
        val directives = readDirectives(source)
        val issues = ArrayList<Issue>()

        val flavor = header?.let { raw ->
            val normalized = SUPPORTED_FLAVORS[raw.lowercase()]
            if (normalized == null) {
                issues += Issue(
                    code = "unsupported-flavor",
                    message = "未知的兼容目标: $raw；当前支持 autojs6",
                )
                null
            } else {
                normalized
            }
        }

        val unsupported = directives.filter { it in UNSUPPORTED_DIRECTIVES }
        if (unsupported.isNotEmpty()) {
            issues += Issue(
                code = "unsupported-directive",
                message = "兼容层暂不支持: " + unsupported.joinToString(", "),
            )
        }

        // 阻断项只在兼容层真正启用时才有意义；普通脚本跳过整份源码的信号扫描，
        // 避免每次运行都为大文件做一次 maskNonCode + 多条正则。
        val signals = if (flavor != null) detectSignals(source, directives) else emptyList()
        if (flavor != null) {
            BLOCKING_SIGNALS.forEach { (signal, issue) ->
                if (signals.any { it.code == signal }) {
                    issues += Issue(code = issue.first, message = issue.second)
                }
            }
        }

        return Analysis(
            enabled = flavor != null,
            flavor = flavor,
            directives = directives,
            issues = issues,
            signals = signals,
        )
    }

    /**
     * 编辑器提示用：兼容未启用时补扫旧脚本信号。
     *
     * [analyze] 为避免普通脚本的运行期开销不再扫描未启用脚本；编辑器需要这些信号
     * 才能提示迁移，因此单独补一次。
     */
    fun analyzeForEditor(source: String): Analysis {
        val analysis = analyze(source)
        return if (analysis.enabled) analysis
        else analysis.copy(signals = detectLegacySignals(source))
    }

    /** 编辑器提示用：只关心“像不像旧脚本”，不要求已写入兼容标记。 */
    fun detectLegacySignals(source: String): List<Signal> =
        detectSignals(source, readDirectives(source))

    private fun findHeader(source: String): String? {
        val window = source.take(SCAN_LIMIT)
        return HEADER_PATTERN.find(window)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 读取脚本开头的字符串字面量指令（`"ui";` / `"auto";` 等）。
     * 遇到第一个不是指令的字符串就停止，避免把 `'use strict'` 之类误当成指令。
     */
    private fun readDirectives(source: String): Set<String> {
        val out = LinkedHashSet<String>()
        val limit = minOf(source.length, SCAN_LIMIT)
        var index = if (source.startsWith('\uFEFF')) 1 else 0

        while (index < limit && out.size < 8) {
            while (index < limit && source[index].isWhitespace()) index++
            if (index >= limit) break

            if (source.startsWith("//", index)) {
                index = source.indexOf('\n', index).let {
                    if (it < 0 || it >= limit) limit else it + 1
                }
                continue
            }
            if (source.startsWith("/*", index)) {
                val end = source.indexOf("*/", index + 2)
                index = if (end < 0 || end >= limit) limit else end + 2
                continue
            }

            val quote = source[index]
            if (quote != '"' && quote != '\'' && quote != '`') break

            val value = StringBuilder()
            index++
            var closed = false
            while (index < limit) {
                val ch = source[index]
                if (ch == '\\' && index + 1 < limit) {
                    value.append(source[index + 1])
                    index += 2
                    continue
                }
                if (ch == quote) {
                    index++
                    closed = true
                    break
                }
                value.append(ch)
                index++
            }
            if (!closed) break

            while (index < limit && source[index].isWhitespace()) index++
            if (index < limit && source[index] == ';') index++

            val tokens = value.toString().trim()
                .split(Regex("""\s+"""))
                .filter { it.isNotEmpty() }
            if (tokens.isEmpty() || tokens.any { it.lowercase() !in KNOWN_DIRECTIVES }) break
            tokens.forEach { out += it.lowercase() }
        }
        return out
    }

    private fun detectSignals(source: String, directives: Set<String>): List<Signal> {
        // 注释与字符串里的 API 名字不应该触发行提示；这里先把它们替换成空格。
        val code = maskNonCode(source)
        val signals = LinkedHashSet<Signal>()

        if ("ui" in directives || "ui-thread" in directives) {
            signals += Signal("ui-mode", "\"ui\"; UI 模式")
        }
        if ("auto" in directives) {
            signals += Signal("auto-directive", "\"auto\"; 指令")
        }
        addIf(
            signals, code,
            Regex("""\b(?:Packages|importClass|importPackage|JavaAdapter)\b"""),
            "java-interop", "Java 互操作",
        )
        addIf(
            signals, code,
            Regex(
                """\b(?:text|desc|id|className|packageName|textContains|textStartsWith|""" +
                    """textEndsWith|textMatches|descContains|descStartsWith|descEndsWith|""" +
                    """descMatches)\s*\(""",
            ),
            "legacy-selector", "Auto.js 选择器",
        )
        addIf(
            signals, code,
            Regex("""\.(?:findOnce|findOne|untilFind|untilFindOne)\s*\("""),
            "legacy-selector-chain", "UiObject 链式调用",
        )
        addIf(
            signals, code,
            Regex("""\b(?:setTimeout|setInterval|setImmediate)\s*\("""),
            "legacy-timers", "setTimeout / setInterval",
        )
        addIf(
            signals, code,
            Regex("""\b(?:threads|engines)\s*\."""),
            "legacy-threads", "threads / engines",
        )
        addIf(
            signals, code,
            Regex(
                """\bui\s*\.\s*(?:layout|layoutFile|setContentView|run|post)\b|""" +
                    """\bfloaty\s*\.\s*(?:window|rawWindow)\b""",
            ),
            "legacy-ui", "ui / floaty XML",
        )
        addIf(
            signals, code,
            Regex(
                """<(?:vertical|horizontal|frame|linear|text|button|input|checkbox|""" +
                    """switch|progressbar|scroll|list|card|appbar|toolbar)(?=[\s/>])""",
            ),
            "legacy-e4x", "E4X XML",
        )
        addIf(
            signals, code,
            Regex(
                """\bdevice\s*\.\s*(?:width|height|rotation|density|brand|model|sdkInt)\b""" +
                    """(?!\s*\()""",
            ),
            "legacy-device-property", "device.width 属性写法",
        )
        addIf(
            signals, code,
            Regex(
                """\bimages\s*\.\s*(?:read|captureScreen|pixel|save|clip|resize|""" +
                    """matchTemplate)\b""",
            ),
            "legacy-images", "images 对象 API",
        )
        addIf(
            signals, code,
            Regex("""\bstorages\s*\.\s*create\b"""),
            "legacy-storage", "storages",
        )
        addIf(
            signals, code,
            Regex("""\b(?:dialogs\s*\.|alert\s*\(|confirm\s*\(|prompt\s*\()"""),
            "legacy-dialogs", "dialogs",
        )
        // require 的字符串参数会被 mask 掉，这里回到原文匹配相对路径。
        addIf(
            signals, source,
            Regex("""\brequire\s*\(\s*['"](?:\.{1,2}/)"""),
            "legacy-require-relative", "多文件 require",
        )
        return signals.toList()
    }

    private fun addIf(
        out: MutableSet<Signal>,
        source: String,
        pattern: Regex,
        code: String,
        label: String,
    ) {
        if (pattern.containsMatchIn(source)) out += Signal(code, label)
    }

    private fun maskNonCode(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0
        while (index < source.length) {
            val ch = source[index]
            when {
                ch == '/' && index + 1 < source.length && source[index + 1] == '/' -> {
                    out.append(' ').append(' ')
                    index += 2
                    while (index < source.length && source[index] != '\n') {
                        out.append(' ')
                        index++
                    }
                }

                ch == '/' && index + 1 < source.length && source[index + 1] == '*' -> {
                    out.append(' ').append(' ')
                    index += 2
                    while (index < source.length &&
                        !(source[index] == '*' && index + 1 < source.length &&
                            source[index + 1] == '/')
                    ) {
                        out.append(if (source[index] == '\n') '\n' else ' ')
                        index++
                    }
                    if (index < source.length) {
                        out.append(' ').append(' ')
                        index += 2
                    }
                }

                ch == '`' -> {
                    // 模板字符串的普通文本不是代码，但 ${...} 里可能是 API 调用；
                    // 保留插值表达式，避免漏检 text("x") 之类的写法。
                    out.append(' ')
                    index++
                    while (index < source.length) {
                        val current = source[index]
                        if (current == '\\' && index + 1 < source.length) {
                            out.append(' ').append(' ')
                            index += 2
                            continue
                        }
                        if (current == '`') {
                            out.append(' ')
                            index++
                            break
                        }
                        if (current == '$' && index + 1 < source.length && source[index + 1] == '{') {
                            val end = interpolationEnd(source, index + 2)
                            if (end < 0) {
                                out.append(" ".repeat(source.length - index))
                                index = source.length
                            } else {
                                out.append(source, index, end + 1)
                                index = end + 1
                            }
                            continue
                        }
                        out.append(if (current == '\n') '\n' else ' ')
                        index++
                    }
                }

                ch == '"' || ch == '\'' -> {
                    val quote = ch
                    out.append(' ')
                    index++
                    while (index < source.length) {
                        val current = source[index]
                        if (current == '\\' && index + 1 < source.length) {
                            out.append(' ').append(' ')
                            index += 2
                            continue
                        }
                        if (current == quote) {
                            out.append(' ')
                            index++
                            break
                        }
                        out.append(if (current == '\n') '\n' else ' ')
                        index++
                    }
                }

                else -> {
                    out.append(ch)
                    index++
                }
            }
        }
        return out.toString()
    }

    /** 找到 `${` 对应的 `}`（处理嵌套与字符串），失败返回 -1。 */
    private fun interpolationEnd(source: String, start: Int): Int {
        var depth = 1
        var index = start
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }

                '\'', '"', '`' -> {
                    index = skipQuoted(source, index)
                    continue
                }

                '/' -> {
                    if (index + 1 < source.length && source[index + 1] == '/') {
                        index = source.indexOf('\n', index + 2).let {
                            if (it < 0) source.length else it
                        }
                        continue
                    }
                    if (index + 1 < source.length && source[index + 1] == '*') {
                        val end = source.indexOf("*/", index + 2)
                        index = if (end < 0) source.length else end + 2
                        continue
                    }
                }
            }
            index++
        }
        return -1
    }

    private fun skipQuoted(source: String, start: Int): Int {
        val quote = source[start]
        var index = start + 1
        while (index < source.length) {
            when (source[index]) {
                '\\' -> index += 2
                quote -> return index + 1
                else -> index++
            }
        }
        return source.length
    }
}
