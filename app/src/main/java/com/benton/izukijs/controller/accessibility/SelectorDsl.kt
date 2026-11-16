package com.benton.izukijs.controller.accessibility

import com.benton.izukijs.controller.NodeSnapshot

/**
 * 选择器字符串 DSL 的解析与条件拆解。
 *
 * 支持：
 * - `text=登录` / `text~=登` / `text^=登` / `text$=录` / `text=/^登.*$/i`
 * - `desc=` / `desc~=` / `desc^=` / `desc$=` / `desc=/.../`
 * - `id=` / `class=` / `package=`
 * - `clickable` / `!clickable` 以及 `clickable=true` 等布尔写法
 *
 * 多个条件以空格分隔，语义为 AND；含空格的值用单/双引号包裹。
 */
internal object SelectorDsl {

    fun parse(dsl: String): AccessibilityController.NodeCriteria {
        val tokens = tokenize(dsl)
        if (tokens.isEmpty()) return AccessibilityController.NodeCriteria()

        var viewId: String? = null
        var text: String? = null
        var textContains: String? = null
        var textRegex: String? = null
        var desc: String? = null
        var descContains: String? = null
        var descRegex: String? = null
        var className: String? = null
        var packageName: String? = null
        var clickable: Boolean? = null
        var editable: Boolean? = null
        var scrollable: Boolean? = null
        var enabled: Boolean? = null
        var visible: Boolean? = null

        fun setBoolean(key: String, value: Boolean) {
            when (key) {
                "clickable" -> clickable = value
                "editable" -> editable = value
                "scrollable" -> scrollable = value
                "enabled" -> enabled = value
                "visible" -> visible = value
                else -> throw IllegalArgumentException("未知布尔字段: $key")
            }
        }

        for (token in tokens) {
            if (token.startsWith("!") && token.length > 1) {
                setBoolean(token.substring(1), false)
                continue
            }
            val op = findOperator(token)
            if (op == null) {
                setBoolean(token, true)
                continue
            }
            val key = token.substring(0, op.first)
            val raw = token.substring(op.second).trim()
            if (raw.isEmpty()) throw IllegalArgumentException("选择器字段 $key 缺少值")
            when (key) {
                "text" -> when (op.third) {
                    "~" -> textContains = raw
                    "^" -> textRegex = "^" + Regex.escape(raw)
                    "$" -> textRegex = Regex.escape(raw) + "$"
                    else -> {
                        val regex = regexLiteral(raw)
                        if (regex != null) textRegex = regex else text = raw
                    }
                }

                "textContains" -> textContains = raw
                "textRegex" -> textRegex = regexLiteral(raw) ?: compile(raw)
                "desc", "description", "contentDescription" -> when (op.third) {
                    "~" -> descContains = raw
                    "^" -> descRegex = "^" + Regex.escape(raw)
                    "$" -> descRegex = Regex.escape(raw) + "$"
                    else -> {
                        val regex = regexLiteral(raw)
                        if (regex != null) descRegex = regex else desc = raw
                    }
                }

                "descContains" -> descContains = raw
                "descRegex" -> descRegex = regexLiteral(raw) ?: compile(raw)
                "id", "viewId" -> viewId = raw
                "class", "className" -> className = raw
                "package", "packageName" -> packageName = raw
                "clickable" -> clickable = bool(raw, key)
                "editable" -> editable = bool(raw, key)
                "scrollable" -> scrollable = bool(raw, key)
                "enabled" -> enabled = bool(raw, key)
                "visible" -> visible = bool(raw, key)
                else -> throw IllegalArgumentException("未知选择器字段: $key")
            }
        }

        textRegex?.let(::compile)
        descRegex?.let(::compile)
        return AccessibilityController.NodeCriteria(
            viewId = viewId,
            text = text,
            textContains = textContains,
            textRegex = textRegex,
            desc = desc,
            descContains = descContains,
            descRegex = descRegex,
            className = className,
            clickable = clickable,
            editable = editable,
            scrollable = scrollable,
            enabled = enabled,
            visible = visible,
            packageName = packageName,
        )
    }

    /** 按字段顺序拆成可逐条统计的条件，供 explain 使用。 */
    fun conditions(criteria: AccessibilityController.NodeCriteria): List<Condition> = buildList {
        criteria.viewId?.let { id -> add(Condition("id", id) { it.viewId == id }) }
        criteria.text?.let { text -> add(Condition("text", text) { it.text == text }) }
        criteria.textContains?.let { value ->
            add(Condition("textContains", value) { it.text?.contains(value) == true })
        }
        criteria.textRegex?.let { pattern ->
            val regex = runCatching { Regex(pattern) }.getOrNull()
            add(Condition("textRegex", pattern) { regex?.containsMatchIn(it.text.orEmpty()) == true })
        }
        criteria.desc?.let { value ->
            add(Condition("desc", value) { it.contentDescription == value })
        }
        criteria.descContains?.let { value ->
            add(Condition("descContains", value) { it.contentDescription?.contains(value) == true })
        }
        criteria.descRegex?.let { pattern ->
            val regex = runCatching { Regex(pattern) }.getOrNull()
            add(Condition("descRegex", pattern) {
                regex?.containsMatchIn(it.contentDescription.orEmpty()) == true
            })
        }
        criteria.className?.let { value ->
            add(Condition("class", value) { it.className == value })
        }
        criteria.clickable?.let { value ->
            add(Condition("clickable", value.toString()) { it.clickable == value })
        }
        criteria.editable?.let { value ->
            add(Condition("editable", value.toString()) { it.editable == value })
        }
        criteria.scrollable?.let { value ->
            add(Condition("scrollable", value.toString()) { it.scrollable == value })
        }
        criteria.enabled?.let { value ->
            add(Condition("enabled", value.toString()) { it.enabled == value })
        }
        criteria.visible?.let { value ->
            add(Condition("visible", value.toString()) { it.visible == value })
        }
        criteria.packageName?.let { value ->
            add(Condition("package", value) { it.packageName == value })
        }
    }

    data class Condition(
        val key: String,
        val value: String,
        val test: (NodeSnapshot) -> Boolean,
    )

    private fun tokenize(dsl: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (ch in dsl) {
            if (quote != null) {
                when {
                    escaped -> {
                        current.append(ch)
                        escaped = false
                    }

                    ch == '\\' -> escaped = true
                    ch == quote -> quote = null
                    else -> current.append(ch)
                }
                continue
            }
            when {
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> if (current.isNotEmpty()) {
                    tokens.add(current.toString())
                    current.clear()
                }

                else -> current.append(ch)
            }
        }
        if (escaped) current.append('\\')
        if (quote != null) throw IllegalArgumentException("选择器 DSL 引号未闭合")
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    /**
     * 返回 `(操作符起点, 值起点, 操作符类型)`。
     * 操作符类型用于 `^` / `$` 的前后缀转换。
     */
    private fun findOperator(token: String): Triple<Int, Int, String>? {
        val candidates = listOf("~=" to "~", "^=" to "^", "$=" to "$", "=" to "=")
        for ((text, type) in candidates) {
            val index = token.indexOf(text)
            if (index > 0) {
                return Triple(index, index + text.length, type)
            }
        }
        return null
    }

    private fun regexLiteral(raw: String): String? {
        if (!raw.startsWith("/")) return null
        val last = raw.lastIndexOf('/')
        if (last <= 0) throw IllegalArgumentException("正则缺少结束斜杠: $raw")
        val pattern = raw.substring(1, last)
        val flags = raw.substring(last + 1)
        return buildString {
            if (flags.contains('i')) append("(?i)")
            if (flags.contains('m')) append("(?m)")
            if (flags.contains('s')) append("(?s)")
            append(pattern)
        }.also(::compile)
    }

    private fun compile(pattern: String): String {
        runCatching { Regex(pattern) }.getOrElse {
            throw IllegalArgumentException("不是合法的正则表达式: $pattern")
        }
        return pattern
    }

    private fun bool(raw: String, key: String): Boolean = when (raw.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> throw IllegalArgumentException("$key 需要 true/false，实际为 $raw")
    }
}
