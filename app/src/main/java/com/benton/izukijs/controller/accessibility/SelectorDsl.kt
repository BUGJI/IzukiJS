package com.benton.izukijs.controller.accessibility

import com.benton.izukijs.controller.NodeBounds
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
        var viewIdContains: String? = null
        var viewIdStartsWith: String? = null
        var viewIdEndsWith: String? = null
        var viewIdRegex: String? = null
        var text: String? = null
        var textContains: String? = null
        var textStartsWith: String? = null
        var textEndsWith: String? = null
        var textRegex: String? = null
        var desc: String? = null
        var descContains: String? = null
        var descStartsWith: String? = null
        var descEndsWith: String? = null
        var descRegex: String? = null
        var className: String? = null
        var classNameContains: String? = null
        var classNameStartsWith: String? = null
        var classNameEndsWith: String? = null
        var classNameRegex: String? = null
        var packageName: String? = null
        var packageNameContains: String? = null
        var packageNameStartsWith: String? = null
        var packageNameEndsWith: String? = null
        var packageNameRegex: String? = null
        var clickable: Boolean? = null
        var longClickable: Boolean? = null
        var editable: Boolean? = null
        var scrollable: Boolean? = null
        var enabled: Boolean? = null
        var visible: Boolean? = null
        var checkable: Boolean? = null
        var checked: Boolean? = null
        var focusable: Boolean? = null
        var focused: Boolean? = null
        var selected: Boolean? = null
        var password: Boolean? = null
        var bounds: NodeBounds? = null
        var boundsInside: NodeBounds? = null
        var boundsContains: NodeBounds? = null

        fun setBoolean(key: String, value: Boolean) {
            when (key) {
                "clickable" -> clickable = value
                "longClickable" -> longClickable = value
                "editable" -> editable = value
                "scrollable" -> scrollable = value
                "enabled" -> enabled = value
                "visible" -> visible = value
                "checkable" -> checkable = value
                "checked" -> checked = value
                "focusable" -> focusable = value
                "focused" -> focused = value
                "selected" -> selected = value
                "password" -> password = value
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
                "textStartsWith" -> textStartsWith = raw
                "textEndsWith" -> textEndsWith = raw
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
                "descStartsWith" -> descStartsWith = raw
                "descEndsWith" -> descEndsWith = raw
                "descRegex" -> descRegex = regexLiteral(raw) ?: compile(raw)
                "id", "viewId" -> when (op.third) {
                    "~" -> viewIdContains = raw
                    "^" -> viewIdStartsWith = raw
                    "$" -> viewIdEndsWith = raw
                    else -> {
                        val regex = regexLiteral(raw)
                        if (regex != null) viewIdRegex = regex else viewId = raw
                    }
                }

                "idContains", "viewIdContains" -> viewIdContains = raw
                "idStartsWith", "viewIdStartsWith" -> viewIdStartsWith = raw
                "idEndsWith", "viewIdEndsWith" -> viewIdEndsWith = raw
                "idMatches", "viewIdMatches" -> viewIdRegex = regexLiteral(raw) ?: compile(raw)
                "class", "className" -> when (op.third) {
                    "~" -> classNameContains = raw
                    "^" -> classNameStartsWith = raw
                    "$" -> classNameEndsWith = raw
                    else -> {
                        val regex = regexLiteral(raw)
                        if (regex != null) classNameRegex = regex else className = raw
                    }
                }

                "classContains", "classNameContains" -> classNameContains = raw
                "classStartsWith", "classNameStartsWith" -> classNameStartsWith = raw
                "classEndsWith", "classNameEndsWith" -> classNameEndsWith = raw
                "classMatches", "classNameMatches" -> classNameRegex = regexLiteral(raw) ?: compile(raw)
                "package", "packageName" -> when (op.third) {
                    "~" -> packageNameContains = raw
                    "^" -> packageNameStartsWith = raw
                    "$" -> packageNameEndsWith = raw
                    else -> {
                        val regex = regexLiteral(raw)
                        if (regex != null) packageNameRegex = regex else packageName = raw
                    }
                }

                "packageContains", "packageNameContains" -> packageNameContains = raw
                "packageStartsWith", "packageNameStartsWith" -> packageNameStartsWith = raw
                "packageEndsWith", "packageNameEndsWith" -> packageNameEndsWith = raw
                "packageMatches", "packageNameMatches" -> packageNameRegex = regexLiteral(raw) ?: compile(raw)
                "clickable" -> clickable = bool(raw, key)
                "longClickable" -> longClickable = bool(raw, key)
                "editable" -> editable = bool(raw, key)
                "scrollable" -> scrollable = bool(raw, key)
                "enabled" -> enabled = bool(raw, key)
                "visible" -> visible = bool(raw, key)
                "checkable" -> checkable = bool(raw, key)
                "checked" -> checked = bool(raw, key)
                "focusable" -> focusable = bool(raw, key)
                "focused" -> focused = bool(raw, key)
                "selected" -> selected = bool(raw, key)
                "password" -> password = bool(raw, key)
                "bounds" -> bounds = parseBounds(raw)
                "boundsInside" -> boundsInside = parseBounds(raw)
                "boundsContains" -> boundsContains = parseBounds(raw)
                else -> throw IllegalArgumentException("未知选择器字段: $key")
            }
        }

        textRegex?.let(::compile)
        descRegex?.let(::compile)
        viewIdRegex?.let(::compile)
        classNameRegex?.let(::compile)
        packageNameRegex?.let(::compile)
        return AccessibilityController.NodeCriteria(
            viewId = viewId,
            viewIdContains = viewIdContains,
            viewIdStartsWith = viewIdStartsWith,
            viewIdEndsWith = viewIdEndsWith,
            viewIdRegex = viewIdRegex,
            text = text,
            textContains = textContains,
            textStartsWith = textStartsWith,
            textEndsWith = textEndsWith,
            textRegex = textRegex,
            desc = desc,
            descContains = descContains,
            descStartsWith = descStartsWith,
            descEndsWith = descEndsWith,
            descRegex = descRegex,
            className = className,
            classNameContains = classNameContains,
            classNameStartsWith = classNameStartsWith,
            classNameEndsWith = classNameEndsWith,
            classNameRegex = classNameRegex,
            clickable = clickable,
            longClickable = longClickable,
            editable = editable,
            scrollable = scrollable,
            enabled = enabled,
            visible = visible,
            checkable = checkable,
            checked = checked,
            focusable = focusable,
            focused = focused,
            selected = selected,
            password = password,
            packageName = packageName,
            packageNameContains = packageNameContains,
            packageNameStartsWith = packageNameStartsWith,
            packageNameEndsWith = packageNameEndsWith,
            packageNameRegex = packageNameRegex,
            bounds = bounds,
            boundsInside = boundsInside,
            boundsContains = boundsContains,
        )
    }

    /** 按字段顺序拆成可逐条统计的条件，供 explain 使用。 */
    fun conditions(criteria: AccessibilityController.NodeCriteria): List<Condition> = buildList {
        criteria.viewId?.let { id -> add(Condition("id", id) { it.viewId == id }) }
        criteria.viewIdContains?.let { value ->
            add(Condition("idContains", value) { it.viewId?.contains(value) == true })
        }
        criteria.viewIdStartsWith?.let { value ->
            add(Condition("idStartsWith", value) { it.viewId?.startsWith(value) == true })
        }
        criteria.viewIdEndsWith?.let { value ->
            add(Condition("idEndsWith", value) { it.viewId?.endsWith(value) == true })
        }
        criteria.viewIdRegex?.let { pattern ->
            val regex = runCatching { Regex(pattern) }.getOrNull()
            add(Condition("idMatches", pattern) { regex?.containsMatchIn(it.viewId.orEmpty()) == true })
        }
        criteria.text?.let { text -> add(Condition("text", text) { it.text == text }) }
        criteria.textContains?.let { value ->
            add(Condition("textContains", value) { it.text?.contains(value) == true })
        }
        criteria.textStartsWith?.let { value ->
            add(Condition("textStartsWith", value) { it.text?.startsWith(value) == true })
        }
        criteria.textEndsWith?.let { value ->
            add(Condition("textEndsWith", value) { it.text?.endsWith(value) == true })
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
        criteria.descStartsWith?.let { value ->
            add(Condition("descStartsWith", value) { it.contentDescription?.startsWith(value) == true })
        }
        criteria.descEndsWith?.let { value ->
            add(Condition("descEndsWith", value) { it.contentDescription?.endsWith(value) == true })
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
        criteria.classNameContains?.let { value ->
            add(Condition("classContains", value) { it.className?.contains(value) == true })
        }
        criteria.classNameStartsWith?.let { value ->
            add(Condition("classStartsWith", value) { it.className?.startsWith(value) == true })
        }
        criteria.classNameEndsWith?.let { value ->
            add(Condition("classEndsWith", value) { it.className?.endsWith(value) == true })
        }
        criteria.classNameRegex?.let { pattern ->
            val regex = runCatching { Regex(pattern) }.getOrNull()
            add(Condition("classMatches", pattern) {
                regex?.containsMatchIn(it.className.orEmpty()) == true
            })
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
        criteria.packageNameContains?.let { value ->
            add(Condition("packageContains", value) { it.packageName?.contains(value) == true })
        }
        criteria.packageNameStartsWith?.let { value ->
            add(Condition("packageStartsWith", value) { it.packageName?.startsWith(value) == true })
        }
        criteria.packageNameEndsWith?.let { value ->
            add(Condition("packageEndsWith", value) { it.packageName?.endsWith(value) == true })
        }
        criteria.packageNameRegex?.let { pattern ->
            val regex = runCatching { Regex(pattern) }.getOrNull()
            add(Condition("packageMatches", pattern) {
                regex?.containsMatchIn(it.packageName.orEmpty()) == true
            })
        }
        criteria.longClickable?.let { value ->
            add(Condition("longClickable", value.toString()) { it.longClickable == value })
        }
        criteria.checkable?.let { value ->
            add(Condition("checkable", value.toString()) { it.checkable == value })
        }
        criteria.checked?.let { value ->
            add(Condition("checked", value.toString()) { it.checked == value })
        }
        criteria.focusable?.let { value ->
            add(Condition("focusable", value.toString()) { it.focusable == value })
        }
        criteria.focused?.let { value ->
            add(Condition("focused", value.toString()) { it.focused == value })
        }
        criteria.selected?.let { value ->
            add(Condition("selected", value.toString()) { it.selected == value })
        }
        criteria.password?.let { value ->
            add(Condition("password", value.toString()) { it.password == value })
        }
        criteria.bounds?.let { value ->
            add(Condition("bounds", "${value.left},${value.top},${value.right},${value.bottom}") {
                it.left == value.left && it.top == value.top &&
                    it.right == value.right && it.bottom == value.bottom
            })
        }
        criteria.boundsInside?.let { value ->
            add(Condition("boundsInside", "${value.left},${value.top},${value.right},${value.bottom}") {
                it.left >= value.left && it.top >= value.top &&
                    it.right <= value.right && it.bottom <= value.bottom
            })
        }
        criteria.boundsContains?.let { value ->
            add(Condition("boundsContains", "${value.left},${value.top},${value.right},${value.bottom}") {
                it.left <= value.left && it.top <= value.top &&
                    it.right >= value.right && it.bottom >= value.bottom
            })
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

    private fun parseBounds(raw: String): NodeBounds {
        val parts = raw.split(',').map { it.trim().toIntOrNull() }
        if (parts.size != 4 || parts.any { it == null }) {
            throw IllegalArgumentException("bounds 需要 left,top,right,bottom，实际为 $raw")
        }
        return NodeBounds(
            left = parts[0]!!,
            top = parts[1]!!,
            right = parts[2]!!,
            bottom = parts[3]!!,
        )
    }
}
