package com.benton.izukijs.data

import java.io.File
import org.json.JSONObject

/**
 * 模块路径解析的纯逻辑，便于用临时目录做 JVM 测试。
 *
 * 只接受 [root] / [scriptsDir] 内的文件；`..` 与绝对路径逃逸都会被拒绝。
 */
internal object ScriptModulePaths {

    fun resolve(
        name: String,
        fromFile: File?,
        root: File,
        scriptsDir: File,
    ): File? {
        val raw = name.trim().replace('\\', '/')
        if (raw.isBlank()) return null
        val bases = ArrayList<File>()
        when {
            raw.startsWith("/") -> bases += File(root, raw.trimStart('/'))
            raw.startsWith("./") || raw.startsWith("../") -> {
                bases += File(fromFile?.parentFile ?: root, raw)
            }

            else -> {
                bases += File(root, raw)
                bases += File(scriptsDir, raw)
            }
        }
        val roots = listOf(root, scriptsDir)
        return bases.firstNotNullOfOrNull { base -> resolveCandidate(base, roots) }
    }

    private fun resolveCandidate(base: File, roots: List<File>): File? {
        val candidates = ArrayList<File>()
        fun add(file: File) {
            if (!candidates.contains(file)) candidates += file
        }
        if (base.extension.isNotEmpty()) add(base)
        add(File(base.parentFile, base.name + ".js"))
        add(File(base.parentFile, base.name + ".mjs"))
        add(File(base.parentFile, base.name + ".cjs"))
        add(File(base.parentFile, base.name + ".json"))
        add(File(base, "index.js"))
        add(File(base, "index.mjs"))
        add(File(base, "index.cjs"))
        add(File(base, "index.json"))
        val packageFile = File(base, "package.json")
        if (packageFile.isFile) {
            val main = runCatching {
                JSONObject(packageFile.readText()).optString("main")
            }.getOrNull().orEmpty().trim()
            if (main.isNotEmpty()) {
                add(File(base, main))
                if (File(base, main).extension.isEmpty()) add(File(base, main + ".js"))
            }
        }
        for (candidate in candidates) {
            safeWithin(candidate, roots)?.let { return it }
        }
        return null
    }

    private fun safeWithin(file: File, roots: List<File>): File? {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        for (root in roots) {
            val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: continue
            if (canonical == canonicalRoot ||
                canonical.path.startsWith(canonicalRoot.path + File.separator)
            ) {
                if (canonical.isFile) return canonical
            }
        }
        return null
    }
}
