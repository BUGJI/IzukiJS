package com.benton.izukijs.data

import java.io.File

/**
 * 脚本根目录的纯路径逻辑，便于用临时目录做 JVM 测试。
 *
 * 根目录下固定使用 `scripts/` 与 `projects/` 两个子目录；这样切换外部目录时
 * 不会把用户目录里的其它内容误识别为脚本或项目。
 */
internal object ScriptStoragePaths {

    const val SCRIPTS_DIR = "scripts"
    const val PROJECTS_DIR = "projects"

    /** 导入 / 复制项目时跳过的目录，与 ZIP 导入保持一致。 */
    val SKIP_DIRECTORIES = setOf("node_modules", ".git", "build", "out", "__MACOSX")

    fun resolveRoot(filesDir: File, path: String?): File {
        val trimmed = path?.trim().orEmpty()
        return if (trimmed.isEmpty()) filesDir else File(trimmed)
    }

    fun scriptsDir(root: File): File = File(root, SCRIPTS_DIR)

    fun projectsDir(root: File): File = File(root, PROJECTS_DIR)

    /**
     * 规范化用户输入的根目录。空白、非绝对路径、文件系统根目录（例如 `/`）
     * 与无法解析的路径一律拒绝，避免全盘扫描或把文件当目录。
     */
    fun normalizeRoot(path: String): File? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return null
        val file = File(trimmed)
        if (!file.isAbsolute) return null
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (canonical.parentFile == null) return null
        return canonical
    }

    /** 两个目录相同或互相包含；用于拒绝会产生递归复制的切换。 */
    fun isSameOrNested(first: File, second: File): Boolean {
        val a = runCatching { first.canonicalFile }.getOrNull() ?: return true
        val b = runCatching { second.canonicalFile }.getOrNull() ?: return true
        return isWithin(a, b) || isWithin(b, a)
    }

    fun isWithin(child: File, parent: File): Boolean =
        child.path == parent.path || child.path.startsWith(parent.path + File.separator)
}
