package com.benton.izukijs.controller.root

import android.content.Context
import com.benton.izukijs.controller.ShellResult
import com.benton.izukijs.controller.shell.ShellRunner
import java.io.File

/**
 * 通过 `su -c` 执行命令的 Root 后端。命令式，无需第三方依赖。
 */
class RootShell(private val context: Context) : ShellRunner {

    @Volatile
    private var available = false

    /** 主动检测 Root 授权（会触发 su 授权弹窗）。阻塞调用，请在后台线程执行。 */
    fun refresh(): Boolean {
        val result = runSu("id")
        available = result?.stdout?.contains("uid=0") == true
        return available
    }

    override fun isAvailable(): Boolean = available

    /** 撤回 Root 后端（本地标记不可用）。 */
    fun markUnavailable() {
        available = false
    }

    override fun run(command: String): ShellResult? = runSu(command)

    override fun screenshotPng(): ByteArray? = runCatching {
        val file = File(context.cacheDir, "izuki_shot.png")
        runSu("screencap -p '${file.absolutePath}'") ?: return null
        if (!file.exists()) return null
        val bytes = file.readBytes()
        file.delete()
        bytes
    }.getOrNull()

    private fun runSu(command: String): ShellResult? = runCatching {
        val process = ProcessBuilder("su", "-c", command).start()
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        ShellResult(stdout, stderr, code)
    }.getOrNull()
}
