package com.benton.izukijs.controller.adb

import com.benton.izukijs.controller.ShellResult
import java.util.concurrent.atomic.AtomicLong

/**
 * 无线 ADB shell 的命令包装与退出码解析。独立出来便于 JVM 单元测试。
 */
internal object AdbShellProtocol {

    val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private val sequence = AtomicLong(0)

    fun newMarker(): String = "__IZUKI_EXIT_${System.nanoTime()}_${sequence.incrementAndGet()}__"

    /** 用 `sh -c` 隔离用户命令，避免命令里的 `exit` 影响退出码采集。 */
    fun wrap(command: String, marker: String): String =
        "sh -c ${quote(command)}; izuki_ec=\$?; echo; echo '$marker:'\$izuki_ec"

    fun quote(command: String): String = "'" + command.replace("'", "'\\''") + "'"

    fun parse(output: String, marker: String): ShellResult {
        val markerIndex = output.lastIndexOf(marker)
        if (markerIndex < 0) {
            return ShellResult(output.trimEnd('\n'), "", -1)
        }
        val stdout = output.substring(0, markerIndex).trimEnd('\n')
        val exitCode = output.substring(markerIndex + marker.length)
            .trim()
            .removePrefix(":")
            .takeWhile { it.isDigit() || it == '-' }
            .toIntOrNull() ?: -1
        return ShellResult(stdout, "", exitCode)
    }

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= PNG_MAGIC.size && PNG_MAGIC.indices.all { bytes[it] == PNG_MAGIC[it] }
}
