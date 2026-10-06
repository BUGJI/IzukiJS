package com.benton.izukijs.controller.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.benton.izukijs.controller.DeviceController
import com.benton.izukijs.controller.ShellResult
import com.benton.izukijs.model.Capability

/**
 * 基于 Shell 命令的控制后端。通过 `input` / `screencap` 等命令实现手势、按键、文本与截图，
 * 避免直接反射隐藏 API，跨 Android 版本更稳。
 *
 * Shizuku 与 Root 都复用本类，仅 [ShellRunner] 不同。
 */
abstract class CommandDeviceController(
    protected val context: Context,
    protected val shell: ShellRunner,
) : DeviceController {

    protected open val supportedCapabilities: Set<Capability> = setOf(
        Capability.GESTURE,
        Capability.KEY,
        Capability.TEXT,
        Capability.SCREENSHOT,
        Capability.SHELL,
        Capability.GLOBAL_ACTION,
    )

    override fun capabilities(): Set<Capability> = supportedCapabilities

    override fun isReady(): Boolean = shell.isAvailable()

    override fun click(x: Float, y: Float): Boolean =
        exec("input tap ${x.toInt()} ${y.toInt()}")

    override fun longClick(x: Float, y: Float, durationMs: Long): Boolean {
        val ix = x.toInt()
        val iy = y.toInt()
        return exec("input swipe $ix $iy $ix $iy ${durationMs.coerceAtLeast(400L)}")
    }

    override fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
    ): Boolean = exec(
        "input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} ${durationMs.coerceAtLeast(1L)}",
    )

    override fun pressKey(keyCode: Int): Boolean = exec("input keyevent $keyCode")

    override fun inputText(text: String): Boolean = exec("input text ${escapeForInputText(text)}")

    override fun globalAction(action: Int): Boolean = exec(globalActionCommand(action))

    override fun screenshot(): Bitmap? {
        val bytes = shell.screenshotPng() ?: return null
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }

    override fun shell(cmd: String): ShellResult? = shell.run(cmd)

    protected fun exec(command: String): Boolean {
        val result = shell.run(command) ?: return false
        return result.exitCode == 0
    }

    private fun globalActionCommand(action: Int): String = when (action) {
        GLOBAL_BACK -> "input keyevent 4"
        GLOBAL_HOME -> "input keyevent 3"
        GLOBAL_RECENTS -> "input keyevent 187"
        GLOBAL_NOTIFICATIONS -> "cmd statusbar expand-notifications"
        else -> "input keyevent $action"
    }

    private fun escapeForInputText(text: String): String {
        val replaced = text.replace(" ", "%s")
        return "'" + replaced.replace("'", "'\\''") + "'"
    }

    protected companion object {
        const val GLOBAL_BACK = 1
        const val GLOBAL_HOME = 2
        const val GLOBAL_RECENTS = 3
        const val GLOBAL_NOTIFICATIONS = 4
    }
}
