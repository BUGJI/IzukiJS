package com.benton.izukijs.data

import java.io.File

/**
 * 切换脚本根目录时的复制逻辑。
 *
 * 只复制扁平 `.js` 脚本与 `projects/` 下的项目目录；目标已存在的文件一律跳过，
 * 不覆盖用户在目标目录中的内容。复制失败只计数，不中断整批操作。
 */
internal object ScriptStorageMigration {

    data class Result(
        val copied: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
    )

    /**
     * 跨文件系统移动目录。
     *
     * ZIP 导入的暂存目录位于应用缓存（内部存储），而脚本根目录可能已切换到共享存储，
     * 两者不在同一挂载点时 `renameTo` 必然失败；这里退化为复制后删除源目录，复制有
     * 任何失败就回滚目标，避免留下半成品。
     */
    fun move(source: File, target: File): Boolean {
        if (target.exists()) return false
        if (source.renameTo(target)) return true
        // 导入场景必须保留完整目录（可能含 build 资源），因此不跳过任何目录。
        val result = copyTree(source, target, skipDirectories = emptySet())
        if (result.failed > 0 || !target.isDirectory) {
            target.deleteRecursively()
            return false
        }
        source.deleteRecursively()
        return true
    }

    fun copy(
        sourceScripts: File,
        sourceProjects: File,
        targetScripts: File,
        targetProjects: File,
    ): Result {
        var copied = 0
        var skipped = 0
        var failed = 0

        val canonicalScripts = runCatching { sourceScripts.canonicalFile }.getOrNull()

        sourceScripts.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.equals("js", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.forEach { source ->
                val canonical = runCatching { source.canonicalFile }.getOrNull()
                if (canonicalScripts == null || canonical == null ||
                    !ScriptStoragePaths.isWithin(canonical, canonicalScripts)
                ) {
                    failed++
                    return@forEach
                }
                when (copyFile(source, File(targetScripts, source.name))) {
                    CopyOutcome.COPIED -> copied++
                    CopyOutcome.SKIPPED -> skipped++
                    CopyOutcome.FAILED -> failed++
                }
            }

        sourceProjects.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && it.name !in ScriptStoragePaths.SKIP_DIRECTORIES }
            ?.sortedBy { it.name }
            ?.forEach { project ->
                val counts = copyTree(project, File(targetProjects, project.name))
                copied += counts.copied
                skipped += counts.skipped
                failed += counts.failed
            }

        return Result(copied = copied, skipped = skipped, failed = failed)
    }

    private fun copyTree(
        source: File,
        target: File,
        skipDirectories: Set<String> = ScriptStoragePaths.SKIP_DIRECTORIES,
    ): Result {
        val canonicalSource = runCatching { source.canonicalFile }.getOrNull()
            ?: return Result(failed = 1)
        var copied = 0
        var skipped = 0
        var failed = 0
        source.walkTopDown()
            .onEnter { dir ->
                dir.name !in skipDirectories &&
                    runCatching { dir.canonicalFile }.getOrNull()?.let {
                        ScriptStoragePaths.isWithin(it, canonicalSource)
                    } != false
            }
            .forEach { file ->
                // 跳过被 onEnter 排除的目录本身，避免在目标目录留下空壳。
                if (file != source && file.name in skipDirectories) return@forEach
                val canonical = runCatching { file.canonicalFile }.getOrNull()
                if (canonical == null || !ScriptStoragePaths.isWithin(canonical, canonicalSource)) {
                    failed++
                    return@forEach
                }
                val destination = if (file == source) {
                    target
                } else {
                    File(target, file.relativeTo(source).invariantSeparatorsPath)
                }
                if (file.isDirectory) {
                    if (!destination.isDirectory && !destination.mkdirs() && !destination.isDirectory) {
                        failed++
                    }
                } else if (file.isFile) {
                    when (copyFile(file, destination)) {
                        CopyOutcome.COPIED -> copied++
                        CopyOutcome.SKIPPED -> skipped++
                        CopyOutcome.FAILED -> failed++
                    }
                }
            }
        return Result(copied = copied, skipped = skipped, failed = failed)
    }

    private fun copyFile(source: File, target: File): CopyOutcome {
        if (target.exists()) return CopyOutcome.SKIPPED
        return runCatching {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = false)
            CopyOutcome.COPIED
        }.getOrDefault(CopyOutcome.FAILED)
    }

    private enum class CopyOutcome { COPIED, SKIPPED, FAILED }
}
