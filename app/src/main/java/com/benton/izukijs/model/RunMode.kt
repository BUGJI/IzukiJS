package com.benton.izukijs.model

/**
 * 脚本的运行模式。
 *
 * [FOREGROUND] 保持现有行为：直接在物理主屏上读屏与注入输入。
 * [BACKGROUND] 在 Shizuku 特权进程创建的虚拟屏上运行目标应用，主屏仍可正常使用。
 */
enum class RunMode {
    FOREGROUND,
    BACKGROUND;

    companion object {
        fun from(raw: String?): RunMode? = entries.firstOrNull {
            it.name.equals(raw?.trim(), ignoreCase = true)
        }
    }
}
