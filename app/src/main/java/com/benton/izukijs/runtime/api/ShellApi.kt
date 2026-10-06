package com.benton.izukijs.runtime.api

import android.webkit.JavascriptInterface
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode

/**
 * Shell API。挂在全局 `shell` 命名空间，仅在 Shizuku 或 Root 就绪时可用。
 */
class ShellApi(private val controllers: ControllerManager) {

    @JavascriptInterface
    fun available(): Boolean = controllers.controllerFor(Capability.SHELL) != null

    @JavascriptInterface
    fun isRoot(): Boolean = ControlMode.ROOT in controllers.readyModes.value

    @JavascriptInterface
    fun exec(command: String): String? {
        val result = controllers.controllerFor(Capability.SHELL)?.shell(command) ?: return null
        return if (result.stdout.isNotBlank()) result.stdout else result.stderr
    }

    @JavascriptInterface
    fun exitCode(command: String): Int {
        val result = controllers.controllerFor(Capability.SHELL)?.shell(command) ?: return -1
        return result.exitCode
    }
}
