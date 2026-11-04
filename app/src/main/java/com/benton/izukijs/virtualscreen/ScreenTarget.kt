package com.benton.izukijs.virtualscreen

import com.benton.izukijs.model.RunMode

/**
 * 脚本操作目标屏。
 *
 * [AUTO] 跟随虚拟屏生命周期：虚拟屏激活时定向到虚拟屏，否则主屏。
 * [VIRTUAL] / [MAIN] 用于显式覆盖，便于脚本在同一次运行里切换捕获与输入目标。
 */
enum class ScreenTarget {
    AUTO,
    VIRTUAL,
    MAIN,
    ;

    fun displayName(): String = name.lowercase()

    companion object {
        /** 脚本默认目标：后台脚本跟随虚拟屏，前台脚本固定主屏。 */
        fun defaultFor(runMode: RunMode): ScreenTarget =
            if (runMode == RunMode.BACKGROUND) AUTO else MAIN

        fun from(value: String?): ScreenTarget? =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}
