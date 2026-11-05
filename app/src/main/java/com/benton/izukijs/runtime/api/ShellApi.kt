package com.benton.izukijs.runtime.api

import android.webkit.JavascriptInterface
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.ShellResult
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.core.log.LogBus
import org.json.JSONObject

/**
 * Shell API。挂在全局 `shell` 命名空间，仅在 Shizuku、无线调试或 Root 就绪时可用。
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

    /** Kotlin 侧结构化执行入口（MCP 工具使用），带审计日志。 */
    fun run(command: String): ShellResult? {
        val result = execute(command)
        if (result == null) {
            logBus.warn("🛡️ shell.exec> ${audit(command)} → 无可用后端 ✗")
        } else {
            logBus.info("🛡️ shell.exec> ${audit(command)} (exit=${result.exitCode})")
        }
        return result
    }

    /** 脚本侧结构化执行：返回 {ok, stdout, stderr, exit_code}。 */
    @JavascriptInterface
    fun execResult(command: String): String {
        val result = execute(command)
        if (result == null) {
            logBus.warn("🛡️ shell.exec> ${audit(command)} → 无可用后端 ✗")
        } else {
            logBus.info("🛡️ shell.execResult> ${audit(command)} (exit=${result.exitCode})")
        }
        return JSONObject().apply {
            if (result == null) {
                put("ok", false)
                put("stdout", "")
                put("stderr", "无可用 Shell 后端")
                put("exit_code", -1)
            } else {
                put("ok", result.exitCode == 0)
                put("stdout", result.stdout)
                put("stderr", result.stderr)
                put("exit_code", result.exitCode)
            }
        }.toString()
    }

    @JavascriptInterface
    fun exec(command: String): String? {
        val result = execute(command)
        if (result == null) {
            logBus.warn("🛡️ shell.exec> ${audit(command)} → 无可用后端 ✗")
            return null
        }
        logBus.info("🛡️ shell.exec> ${audit(command)} (exit=${result.exitCode})")
        return if (result.stdout.isNotBlank()) result.stdout else result.stderr
    }

    @JavascriptInterface
    fun exitCode(command: String): Int {
        val result = execute(command)
        if (result == null) {
            logBus.warn("🛡️ shell.exitCode> ${audit(command)} → 无可用后端 ✗")
            return -1
        }
        logBus.info("🛡️ shell.exitCode> ${audit(command)} (exit=${result.exitCode})")
        return result.exitCode
    }

    private fun execute(command: String): ShellResult? =
        controllers.controllerFor(Capability.SHELL)?.shell(command)

    /** 审计用：折叠空白并截断过长命令。 */
    private fun audit(command: String): String {
        val oneLine = command.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= AUDIT_MAX_CHARS) oneLine else oneLine.take(AUDIT_MAX_CHARS) + "…"
    }

    private companion object {
        const val AUDIT_MAX_CHARS = 200
    }
}
