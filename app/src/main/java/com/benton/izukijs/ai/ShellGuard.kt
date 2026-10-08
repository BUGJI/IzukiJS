package com.benton.izukijs.ai

/**
 * Shell 高危命令识别。用于拦截外部 AI 通过 `shell` 工具执行破坏性命令。
 *
 * 只做「危险即拒绝」，不做白名单放行——正常命令仍然直接执行；确需放行时由用户在设置里
 * 显式开启「允许高危 Shell」。纯粹基于正则，不依赖 Android，便于单元测试。
 */
object ShellGuard {

    private data class Rule(val regex: Regex, val label: String)

    private val RULES: List<Rule> = listOf(
        Rule(Regex("""(^|[\s;&|])(reboot|shutdown|poweroff|halt)(\s|$)""", RegexOption.IGNORE_CASE), "重启 / 关机"),
        Rule(Regex("""(^|[\s;&|])mkfs(\.\w+)?(\s|$)""", RegexOption.IGNORE_CASE), "格式化分区"),
        Rule(Regex("""\bdd\b[\s\S]*\bof\s*=\s*/dev/""", RegexOption.IGNORE_CASE), "写入块设备"),
        Rule(Regex("""\brm\b[\s\S]*\s-[a-z]*(rf|fr)[a-z]*\s+/(\s|$|\*)""", RegexOption.IGNORE_CASE), "递归删除根目录"),
        Rule(Regex("""\bpm\s+(uninstall|clear)\b""", RegexOption.IGNORE_CASE), "卸载 / 清除应用数据"),
        Rule(Regex("""\bsettings\s+put\b""", RegexOption.IGNORE_CASE), "修改系统设置"),
        Rule(Regex("""\bmount\b[\s\S]*\bremount\b""", RegexOption.IGNORE_CASE), "重挂载系统分区"),
        Rule(Regex(""">\s*/dev/block/""", RegexOption.IGNORE_CASE), "写入块设备"),
    )

    /** 命中危险规则时返回原因描述，否则返回 null。 */
    fun reason(command: String): String? {
        val cmd = command.trim()
        if (cmd.isEmpty()) return null
        return RULES.firstOrNull { it.regex.containsMatchIn(cmd) }?.label
    }

    fun isDangerous(command: String): Boolean = reason(command) != null
}
