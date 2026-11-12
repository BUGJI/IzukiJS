package com.benton.izukijs.runtime

import android.content.Context
import java.io.File

/**
 * 缓存图片统计与清理。
 *
 * 只处理脚本截图 / 模板等已知前缀的临时图片，避免误删导出文件、更新包或扫描导入临时文件。
 */
class CacheImageStore(private val cacheDir: File) {

    constructor(context: Context) : this(context.cacheDir)

    data class Stats(val count: Int, val bytes: Long)

    fun stats(): Stats {
        val files = imageFiles()
        return Stats(files.size, files.sumOf { it.length() })
    }

    fun clear(): Stats {
        var count = 0
        var bytes = 0L
        imageFiles().forEach { file ->
            val size = file.length()
            if (file.delete()) {
                count++
                bytes += size
            }
        }
        return Stats(count, bytes)
    }

    private fun imageFiles(): List<File> =
        cacheDir.listFiles()
            ?.filter { it.isFile && it.name.isCacheImageName() }
            .orEmpty()

    private fun String.isCacheImageName(): Boolean {
        val image = endsWith(".png", ignoreCase = true) ||
            endsWith(".jpg", ignoreCase = true) ||
            endsWith(".jpeg", ignoreCase = true)
        if (!image) return false
        return startsWith("screenshot_") ||
            startsWith("template_") ||
            this == "izuki_shot.png"
    }
}
