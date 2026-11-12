package com.benton.izukijs

import java.io.File

/** 单元测试读取仓库内文件；兼容 Gradle 以仓库根或 `app/` 为工作目录两种情况。 */
object TestProjectFiles {

    fun read(relativePath: String): String = resolve(relativePath).readText(Charsets.UTF_8)

    fun resolve(relativePath: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val direct = File(dir, relativePath)
            if (direct.isFile) return direct
            val inApp = File(dir, "app/$relativePath")
            if (inApp.isFile) return inApp
            dir = dir.parentFile
        }
        error("找不到测试文件: $relativePath")
    }

    fun list(relativeDir: String): List<File> = resolveDirectory(relativeDir).listFiles().orEmpty().toList()

    fun resolveDirectory(relativeDir: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val direct = File(dir, relativeDir)
            if (direct.isDirectory) return direct
            val inApp = File(dir, "app/$relativeDir")
            if (inApp.isDirectory) return inApp
            dir = dir.parentFile
        }
        error("找不到测试目录: $relativeDir")
    }
}
