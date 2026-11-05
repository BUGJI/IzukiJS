package com.benton.izukijs.controller.adb

import com.benton.izukijs.controller.ShellResult
import com.benton.izukijs.controller.shell.ShellRunner
import com.benton.izukijs.core.log.LogBus
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 通过无线调试的 adb shell 执行命令。
 *
 * adbd 的 `shell:` 服务默认分配 PTY，会改写换行并破坏截图二进制；这里统一使用
 * `shell,raw:`，并在命令末尾追加唯一退出码哨兵。stdout / stderr 由 adbd 合并，
 * 因此 [ShellResult.stderr] 恒为空串。
 */
class AdbShell(
    private val connection: AdbConnectionManager,
    private val logBus: LogBus,
) : ShellRunner {

    override fun isAvailable(): Boolean = connection.isReady()

    override fun run(command: String): ShellResult? {
        val marker = AdbShellProtocol.newMarker()
        val bytes = readRaw(AdbShellProtocol.wrap(command, marker)) ?: return null
        val text = String(bytes, Charsets.UTF_8)
        val result = AdbShellProtocol.parse(text, marker)
        if (text.lastIndexOf(marker) < 0) {
            logBus.warn("无线调试 shell 未返回退出码（连接可能中断）")
        }
        return result
    }

    override fun screenshotPng(displayId: Int?): ByteArray? {
        val display = displayId?.takeIf { it >= 0 }?.let { " -d $it" }.orEmpty()
        readRaw("screencap -p$display")
            ?.takeIf(AdbShellProtocol::isPng)
            ?.let { return it }

        // 某些 ROM 的 `screencap -p` 直接输出可能仍被 PTY 改写；落盘再读取更稳。
        val path = "/data/local/tmp/izuki_shot_${System.nanoTime()}.png"
        val shot = run("screencap -p$display $path")
        if (shot == null || shot.exitCode != 0) return null
        val bytes = readRaw("cat $path")
        runCatching { run("rm -f $path") }
        return bytes?.takeIf(AdbShellProtocol::isPng)
    }

    private fun readRaw(command: String): ByteArray? {
        val stream = connection.openShellStream(command) ?: return null
        return runCatching {
            stream.use { it.openInputStream().use(::readAll) }
        }.onFailure {
            logBus.warn("无线调试读取命令输出失败: ${it.message}")
        }.getOrNull()
    }

    /**
     * libadb 在远端关闭且队列已读空时会抛 IOException("Stream closed.") 而不是返回 -1；
     * 这里把它当作正常 EOF，否则大输出（如截图）会在最后一段数据后整体失败。
     */
    private fun readAll(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (e: IOException) {
                if (e.message?.contains("Stream closed", ignoreCase = true) == true) break
                throw e
            }
            if (read < 0) break
            if (read > 0) out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
