package com.benton.izukijs.data

import android.content.Context
import java.io.File

/** 一条脚本历史版本。 */
data class ScriptVersion(
    val file: File,
    val timestamp: Long,
    val size: Long,
)

/**
 * 脚本版本历史。每次落盘前把旧内容留档到应用私有目录，便于误删 / 改坏后恢复。
 *
 * 自动保存较频繁，因此默认做时间节流（[MIN_INTERVAL_MS]），只在内容变化且距离上一条
 * 历史超过该间隔时记录；手动恢复前会调用 [record] 的 force 参数强制留档。
 * 每个脚本最多保留 [MAX_VERSIONS] 条。
 */
class ScriptHistoryRepository(private val context: Context) {

    private val root: File
        get() = File(context.filesDir, HISTORY_DIR).apply { if (!exists()) mkdirs() }

    fun record(scriptId: String, content: String, force: Boolean = false) {
        if (content.isBlank()) return
        val dir = dirFor(scriptId).apply { mkdirs() }
        val versions = listVersions(dir)
        val latest = versions.firstOrNull()
        if (!force && latest != null) {
            val latestText = runCatching { latest.file.readText() }.getOrNull()
            if (latestText == content) return
            if (System.currentTimeMillis() - latest.timestamp < MIN_INTERVAL_MS) return
        }
        var stamp = System.currentTimeMillis()
        while (File(dir, "$stamp.js").exists()) stamp++
        val file = File(dir, "$stamp.js")
        runCatching { file.writeText(content) }
        trim(dir)
    }

    fun list(scriptId: String): List<ScriptVersion> = listVersions(dirFor(scriptId))

    fun read(version: ScriptVersion): String = runCatching { version.file.readText() }.getOrDefault("")

    fun deleteFor(scriptId: String) {
        runCatching { dirFor(scriptId).deleteRecursively() }
    }

    fun move(oldId: String, newId: String) {
        val from = dirFor(oldId)
        if (!from.exists()) return
        val to = dirFor(newId)
        runCatching {
            to.parentFile?.mkdirs()
            if (to.exists()) to.deleteRecursively()
            from.renameTo(to)
        }
    }

    private fun dirFor(scriptId: String): File =
        File(root, scriptId.replace(Regex("""[^A-Za-z0-9._-]"""), "_"))

    private fun listVersions(dir: File): List<ScriptVersion> = dir
        .listFiles { file -> file.isFile && file.name.endsWith(".js") && file.length() > 0L }
        .orEmpty()
        .map { file ->
            ScriptVersion(
                file = file,
                timestamp = file.nameWithoutExtension.toLongOrNull() ?: file.lastModified(),
                size = file.length(),
            )
        }
        .sortedByDescending { it.timestamp }

    private fun trim(dir: File) {
        listVersions(dir).drop(MAX_VERSIONS).forEach { runCatching { it.file.delete() } }
    }

    private companion object {
        const val HISTORY_DIR = "script_history"
        const val MAX_VERSIONS = 20

        /** 自动保存的留档间隔：10 分钟。 */
        const val MIN_INTERVAL_MS = 10L * 60L * 1000L
    }
}
