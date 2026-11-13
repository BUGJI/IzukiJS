package com.benton.izukijs.controller

/**
 * 从 `dumpsys input_method` 输出判断软键盘是否仍然显示。
 *
 * 仅做文本解析，方便在 `dismiss_ime` 后校验按键是否真的生效，避免「按下 ESC 就报成功」
 * 的误报。输出格式随 ROM 略有差异，但 `mInputShown` 是各版本共有的稳定字段。
 */
object ImeStateProbe {

    /** 返回 true / false；无法解析时返回 null（调用方应按「未确认」处理）。 */
    fun isImeShown(dumpsys: String?): Boolean? {
        if (dumpsys.isNullOrBlank()) return null
        var found = false
        var shown = false
        for (match in INPUT_SHOWN_REGEX.findAll(dumpsys)) {
            found = true
            if (match.groupValues[1].equals("true", ignoreCase = true)) {
                shown = true
                break
            }
        }
        return if (found) shown else null
    }

    private val INPUT_SHOWN_REGEX = Regex("""mInputShown\s*=\s*(true|false)""", RegexOption.IGNORE_CASE)
}
