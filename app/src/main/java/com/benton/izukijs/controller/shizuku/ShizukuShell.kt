package com.benton.izukijs.controller.shizuku

import android.content.pm.PackageManager
import com.benton.izukijs.controller.ShellResult
import com.benton.izukijs.controller.shell.ShellRunner
import rikka.shizuku.Shizuku

/**
 * 通过 Shizuku（shell uid 2000）执行命令与截图。
 */
class ShizukuShell : ShellRunner {

    override fun isAvailable(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    override fun run(command: String): ShellResult? = runCatching {
        val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        ShellResult(stdout, stderr, code)
    }.getOrNull()

    override fun screenshotPng(): ByteArray? = runCatching {
        val process = Shizuku.newProcess(arrayOf("screencap", "-p"), null, null)
        val bytes = process.inputStream.use { it.readBytes() }
        process.waitFor()
        bytes
    }.getOrNull()
}
