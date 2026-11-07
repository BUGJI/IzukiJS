package com.benton.izukijs.ai

/**
 * 工具执行安全策略。
 *
 * 集中在一处，确保 AI 直调、MCP 调用以及 `batch` 内嵌动作都走同一套护栏，
 * 避免只在某个入口检查而被其它路径绕过。
 */
data class ToolPolicy(
    val allowShell: Boolean = false,
    /** 允许执行 [ShellGuard] 判定的高危命令；默认关闭。 */
    val allowDangerousShell: Boolean = false,
    /** 禁止启动的应用包名；支持结尾 `*` 前缀通配（如 `com.bank.*`）。 */
    val blockedPackages: List<String> = emptyList(),
) {

    fun isPackageBlocked(packageName: String): Boolean {
        val target = packageName.trim()
        if (target.isBlank()) return false
        return blockedPackages.any { entry ->
            when {
                entry.isBlank() -> false
                entry.endsWith("*") -> target.startsWith(entry.dropLast(1).trim(), ignoreCase = true)
                else -> target.equals(entry.trim(), ignoreCase = true)
            }
        }
    }
}
