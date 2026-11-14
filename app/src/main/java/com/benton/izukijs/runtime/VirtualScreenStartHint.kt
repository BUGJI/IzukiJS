package com.benton.izukijs.runtime

import com.benton.izukijs.virtualscreen.VirtualScreenResolution

/**
 * 从脚本源码中提取 `virtualScreen.start({...})` 的字面量分辨率。
 *
 * 后台模式会在脚本执行前预创建虚拟屏；如果脚本随后又用不同分辨率调用 `start()`，
 * 就会先按设置建一次、再重建一次。这里只识别**数字字面量**参数，让预创建直接使用
 * 脚本想要的尺寸；变量 / 函数返回值等动态参数一律返回 null，回退到设置分辨率。
 */
internal object VirtualScreenStartHint {

    fun parse(source: String, base: VirtualScreenResolution): VirtualScreenResolution? {
        val code = stripCommentsAndStrings(source)
        val call = START_CALL.find(code) ?: return null
        val body = call.groupValues[1]
        val width = intLiteral(body, "width") ?: intLiteral(body, "w") ?: return null
        val height = intLiteral(body, "height") ?: intLiteral(body, "h") ?: return null
        val dpi = intLiteral(body, "dpi") ?: base.dpi
        return VirtualScreenResolution(width, height, dpi).normalized()
    }

    private fun intLiteral(body: String, name: String): Int? =
        Regex("""(?<![A-Za-z0-9_$])${Regex.escape(name)}\s*:\s*(\d+)""")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    /**
     * 把注释与字符串内容替换为空格，避免注释掉的调用或字符串里的示例被误识别。
     * 模板字符串整体跳过（其中的 ${...} 动态表达式不参与字面量解析）。
     */
    private fun stripCommentsAndStrings(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    while (i < source.length && source[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }

                c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                    out.append(' ')
                    out.append(' ')
                    i += 2
                    while (i < source.length) {
                        if (source[i] == '*' && i + 1 < source.length && source[i + 1] == '/') {
                            out.append(' ')
                            out.append(' ')
                            i += 2
                            break
                        }
                        out.append(if (source[i] == '\n') '\n' else ' ')
                        i++
                    }
                }

                c == '"' || c == '\'' || c == '`' -> {
                    val quote = c
                    out.append(' ')
                    i++
                    while (i < source.length) {
                        val current = source[i]
                        if (current == '\\' && i + 1 < source.length) {
                            out.append(' ')
                            out.append(' ')
                            i += 2
                            continue
                        }
                        if (current == quote) {
                            out.append(' ')
                            i++
                            break
                        }
                        out.append(if (current == '\n') '\n' else ' ')
                        i++
                    }
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    // 两端花括号都必须转义：Android 使用 ICU 正则实现，裸 `}` 会直接抛
    // PatternSyntaxException（JVM 的 java.util.regex 更宽松，单测发现不了），
    // 因此单测直接校验模式串的转义形式。
    internal const val START_CALL_PATTERN =
        """virtualScreen\s*\.\s*start\s*\(\s*\{([^}]*)\}"""

    private val START_CALL = Regex(START_CALL_PATTERN)
}
