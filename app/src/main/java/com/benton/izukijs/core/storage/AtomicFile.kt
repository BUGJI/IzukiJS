package com.benton.izukijs.core.storage

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 原子写文件：先写同目录临时文件，再整体替换目标文件。
 *
 * 直接 `writeText` 在写入中途崩溃 / 断电时可能留下半截内容；配置文件与脚本一旦损坏
 * 会影响启动。这里通过「临时文件 + rename」保证目标要么是旧内容，要么是新内容。
 */
object AtomicFile {

    fun writeText(target: File, text: String) {
        val parent = target.absoluteFile.parentFile
            ?: throw IOException("无法确定文件目录: ${target.path}")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("无法创建文件目录: ${parent.path}")
        }
        val prefix = (target.name + ".").padEnd(3, '_')
        val temp = File.createTempFile(prefix, ".tmp", parent)
        try {
            temp.writeText(text)
            moveReplacing(temp, target)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun moveReplacing(source: File, target: File) {
        val sourcePath = source.toPath()
        val targetPath = target.toPath()
        try {
            Files.move(
                sourcePath,
                targetPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
