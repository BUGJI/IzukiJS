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
            logBus.warn("🛡️ shell.exec> ${audit(command)} → 无可用后端 ✗")
            return null
        }
        logBus.info("🛡️ shell.exec> ${audit(command)} (exit=${result.exitCode})")
        return if (result.stdout.isNotBlank()) result.stdout else result.stderr
    }

    @JavascriptInterface
    fun exitCode(command: String): Int {
        val result = controllers.controllerFor(Capability.SHELL)?.shell(command)
        if (result == null) {
            logBus.warn("🛡️ shell.exitCode> ${audit(command)} → 无可用后端 ✗")
            return -1
        }
        logBus.info("🛡️ shell.exitCode> ${audit(command)} (exit=${result.exitCode})")
        return result.exitCode
    }

    /** 审计用：折叠空白并截断过长命令。 */
    private fun audit(command: String): String {
        val oneLine = command.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= AUDIT_MAX_CHARS) oneLine else oneLine.take(AUDIT_MAX_CHARS) + "…"
    }

    private companion object {
        const val AUDIT_MAX_CHARS = 200
    }
}
