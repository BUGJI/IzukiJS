package com.benton.izukijs.controller.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.benton.izukijs.controller.DeviceController
import com.benton.izukijs.controller.ShellResult
import com.benton.izukijs.controller.TextInputMethod
import com.benton.izukijs.model.Capability
import com.benton.izukijs.runtime.LogBus

/**
 * 基于 Shell 命令的控制后端。通过 `input` / `screencap` 等命令实现手势、按键、文本与截图，
 * 避免直接反射隐藏 API，跨 Android 版本更稳。
 *
 * Shizuku 与 Root 都复用本类，仅 [ShellRunner] 不同。每条实际下发的命令都会以
 * DEBUG 级别写入 [logBus]。
 */
abstract class CommandDeviceController(
    protected val context: Context,
    protected val shell: ShellRunner,
    protected val logBus: LogBus,
) : DeviceController {

    /** ADBKeyboard 安装状态的缓存，null 表示尚未探测。 */
    @Volatile
    private var adbKeyboardInstalled: Boolean? = null

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

    /**
     * 文本输入。Shizuku / Root 下 `input text` 只支持 ASCII，且对中文会静默失败；
     * [TextInputMethod.AUTO] 对 ASCII 用 `input text`，非 ASCII 优先「剪贴板 + 粘贴」
     * （无需额外 App），其次 ADBKeyboard 广播；也可用 [method] 显式指定方式。
     */
    override fun inputText(text: String): Boolean = inputText(text, TextInputMethod.AUTO)

    override fun inputText(text: String, method: TextInputMethod): Boolean = when (method) {
        TextInputMethod.INPUT -> inputTextViaInput(text)
        TextInputMethod.CLIPBOARD -> pasteText(text)
        TextInputMethod.BROADCAST -> broadcastText(text)
        TextInputMethod.AUTO -> autoInput(text)
    }

    private fun autoInput(text: String): Boolean {
        val asciiOnly = text.all { it.code in ASCII_PRINTABLE }
        return if (asciiOnly) {
            inputTextViaInput(text) || pasteText(text)
        } else {
            pasteText(text) || broadcastText(text)
        }
    }

    private fun inputTextViaInput(text: String): Boolean =
        exec("input text ${escapeForInputText(text)}")

    /** 写入系统剪贴板后发送粘贴键（KEYCODE_PASTE），依赖当前焦点在可编辑控件上。 */
    private fun pasteText(text: String): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        val written = runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIPBOARD_LABEL, text))
        }.isSuccess
        if (!written) {
            logBus.warn("${mode.displayName} 写入剪贴板失败")
            return false
        }
        return exec("input keyevent $KEYCODE_PASTE")
    }

    /** 通过 ADBKeyboard 广播输入任意 Unicode 文本。 */
    private fun broadcastText(text: String): Boolean {
        if (!hasAdbKeyboard()) {
            logBus.warn("${mode.displayName} 未安装 ADBKeyboard，无法使用广播输入")
            return false
        }
        val base64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return exec("am broadcast -a ADB_INPUT_B64 --es msg $base64") ||
            exec("am broadcast -a ADB_INPUT_TEXT --es msg ${escapeForInputText(text)}")
    }

    /** 是否安装了 ADBKeyboard（缓存结果，避免每次输入都查询）。 */
    private fun hasAdbKeyboard(): Boolean {
        adbKeyboardInstalled?.let { return it }
        val installed = shell.run("pm list packages $ADB_KEYBOARD_PACKAGE")
            ?.stdout?.contains(ADB_KEYBOARD_PACKAGE) == true
        adbKeyboardInstalled = installed
        return installed
    }

    override fun globalAction(action: Int): Boolean = exec(globalActionCommand(action))

    override fun screenshot(): Bitmap? {
        val bytes = shell.screenshotPng() ?: return null
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }

    override fun shell(cmd: String): ShellResult? = shell.run(cmd)

    protected fun exec(command: String): Boolean {
        val result = shell.run(command)
        if (result == null) {
            logBus.debug("${mode.displayName}> $command (未执行) ✗")
            return false
        }
        logBus.debug("${mode.displayName}> $command (exit=${result.exitCode})")
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

        /** `input text` 可处理的可见 ASCII 范围。 */
        val ASCII_PRINTABLE = 0x20..0x7E

        const val ADB_KEYBOARD_PACKAGE = "com.android.adbkeyboard"

        /** KEYCODE_PASTE：触发当前焦点控件粘贴。 */
        const val KEYCODE_PASTE = 279

        const val CLIPBOARD_LABEL = "izuki"
    }
}
