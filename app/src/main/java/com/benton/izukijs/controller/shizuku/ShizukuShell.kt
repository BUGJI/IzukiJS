package com.benton.izukijs.controller.shizuku

import com.benton.izukijs.controller.ShellResult
import com.benton.izukijs.controller.shell.ShellRunner
import com.benton.izukijs.controller.shell.readProcessOutput
import com.benton.izukijs.core.shizuku.ShizukuAuthorization
import rikka.shizuku.Shizuku

/**
 * 通过 Shizuku（shell uid 2000）执行命令与截图。
 */
class ShizukuShell : ShellRunner {

    override fun isAvailable(): Boolean = ShizukuAuthorization.isReady()

    override fun run(command: String): ShellResult? = runCatching {
        val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        val (stdout, stderr) = readProcessOutput(process)
        val code = process.waitFor()
        ShellResult(stdout, stderr, code)
    }.getOrNull()

    override fun screenshotPng(displayId: Int?): ByteArray? = runCatching {
        val command = if (displayId != null && displayId >= 0) {
            arrayOf("screencap", "-p", "-d", displayId.toString())
        } else {
            arrayOf("screencap", "-p")
        }
        val process = Shizuku.newProcess(command, null, null)
        val bytes = process.inputStream.use { it.readBytes() }
        process.waitFor()
        bytes
    }.getOrNull()
}
