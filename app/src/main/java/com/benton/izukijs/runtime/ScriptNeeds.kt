package com.benton.izukijs.runtime

/**
 * 脚本头部的能力声明 `// @need shell, fs`。
 *
 * 没有声明时返回 null，保持旧行为（暴露全部已启用能力）。一旦声明，运行时就只注册
 * 声明过的命名空间，未声明的 `http` / `shell` / `fs` 等 API 在脚本里不存在。
 * 这是「最小暴露」而不是安全沙箱：输入、截图等核心能力仍始终可用。
 */
object ScriptNeeds {

    val KNOWN: Set<String> = setOf(
        "shell",
        "fs",
        "notify",
        "http",
        "clipboard",
        "ai",
        "app",
        "selector",
        "images",
        "ocr",
    )

    private val NEED_LINE = Regex("""(?m)^\s*//\s*@need\s*:?\s*(.+?)\s*$""")

    fun parse(source: String): Set<String>? {
        val matches = NEED_LINE.findAll(source).toList()
        if (matches.isEmpty()) return null
        return matches
            .flatMap { match ->
                // 支持用两个及以上空格 / # 分隔说明文字，例如：
                // // @need shell, fs  读取文件并执行命令
                val body = match.groupValues[1]
                    .split(Regex("""\s{2,}|\s+#\s*"""), limit = 2)
                    .first()
                body.split(',', ';', ' ', '\t')
            }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}
