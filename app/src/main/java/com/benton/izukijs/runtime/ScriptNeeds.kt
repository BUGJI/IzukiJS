package com.benton.izukijs.runtime

import com.benton.izukijs.runtime.api.ScriptApiCatalog

/**
 * 脚本头部的能力声明 `// @need shell, fs`。
 *
 * 没有声明时返回 null，保持旧行为（暴露全部已启用能力）。一旦声明，运行时就只注册
 * 声明过的命名空间，未声明的 `http` / `shell` / `fs` 等 API 在脚本里不存在。
 * 这是「最小暴露」而不是安全沙箱：输入、截图等核心能力仍始终可用。
 *
 * 可声明的名字来自 [ScriptApiCatalog.needKeys]，与 API 注册表保持同步。
 */
object ScriptNeeds {

    val KNOWN: Set<String> = ScriptApiCatalog.needKeys

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
            // 单空格后的中文说明（如 `// @need shell 执行命令`）不是能力名；遇到非 ASCII
            // 文本就停止解析，避免把说明当成未知能力而拒绝运行脚本。ASCII 未知 token 仍保留，
            // 让拼写错误得到明确的 @need 报错。
            .takeWhile { token -> token.all { it.code < 128 } }
            .toSet()
    }
}
