package com.benton.izukijs.virtualscreen

/**
 * 虚拟屏文本输入的纯逻辑：决定走 `input text` 还是剪贴板，以及 Shell 转义。
 *
 * `input text` 只支持可见 ASCII；空格需写成 `%s`，单引号按 shell 规则闭合。
 * 含换行、中文或 emoji 时改走剪贴板粘贴，避免命令静默失败。
 */
object VirtualScreenTextInput {

    private val ASCII_PRINTABLE = 0x20..0x7E

    fun needsClipboard(text: String): Boolean =
        text.any { it.code !in ASCII_PRINTABLE }

    fun escapeForInput(text: String): String {
        val replaced = text.replace(" ", "%s")
        return "'" + replaced.replace("'", "'\\''") + "'"
    }
}
