package com.benton.izukijs.runtime.api

import android.webkit.JavascriptInterface
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.runtime.LogBus

/**
 * Shell API。挂在全局 `shell` 命名空间，仅在 Shizuku 或 Root 就绪时可用。
 *
 * 每条命令都会以 DEBUG 级别写入 [logBus]。
 */
class ShellApi(
    private val controllers: ControllerManager,
    private val logBus: LogBus,
) {

    @JavascriptInterface
    fun available(): Boolean = controllers.controllerFor(Capability.SHELL) != null

    @JavascriptInterface
    fun isRoot(): Boolean = ControlMode.ROOT in controllers.readyModes.value

    @JavascriptInterface
    fun exec(command: String): String? {
        val result = controllers.controllerFor(Capability.SHELL)?.shell(command)
        if (result == null) {
            logBus.debug("shell.exec> $command → 无可用后端 ✗")
            return null
        }
        logBus.debug("shell.exec> $command (exit=${result.exitCode})")
        return if (result.stdout.isNotBlank()) result.stdout else result.stderr
    }

    @JavascriptInterface
    fun exitCode(command: String): Int {
        val result = controllers.controllerFor(Capability.SHELL)?.shell(command)
        if (result == null) {
            logBus.debug("shell.exitCode> $command → 无可用后端 ✗")
            return -1
        }
        logBus.debug("shell.exitCode> $command (exit=${result.exitCode})")
        return result.exitCode
    }
}
