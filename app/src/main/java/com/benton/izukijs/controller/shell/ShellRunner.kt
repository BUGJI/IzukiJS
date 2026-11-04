package com.benton.izukijs.controller.shell

import com.benton.izukijs.controller.ShellResult

/**
 * Shell 执行抽象。Shizuku 与 Root 各提供一种实现，命令式控制后端复用同一套逻辑。
 */
interface ShellRunner {
    fun isAvailable(): Boolean

    fun run(command: String): ShellResult?

    /** 使用高级权限截图，返回 PNG 字节；[displayId] 非空时截取指定显示器。 */
    fun screenshotPng(displayId: Int? = null): ByteArray?
}
