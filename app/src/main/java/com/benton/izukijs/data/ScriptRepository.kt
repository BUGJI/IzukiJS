package com.benton.izukijs.data

import android.content.Context
import com.benton.izukijs.core.storage.AtomicFile
import com.benton.izukijs.model.ScriptInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.json.JSONObject

/** 脚本内容在一次原子更新中的前后状态。 */
data class ScriptUpdateResult(
    val script: ScriptInfo,
    val previousSha256: String,
    val sha256: String,
    val previousLines: Int,
    val lines: Int,
    val changed: Boolean,
)

/** 批量导出条目：zip 内路径与实际文件，读取失败由写入方跳过。 */
data class ScriptArchiveEntry(
    val name: String,
    val file: File,
)

/** `expectedSha256` 与当前内容不一致：说明脚本在读取后被其他人改过。 */
class ScriptStaleException(
    message: String,
    val actualSha256: String,
) : IllegalStateException(message)

/**
 * 脚本仓库。脚本以 `.js` 文件形式保存在当前脚本根目录的 `scripts/` 下，
 * 多文件项目保存在 `projects/` 下；根目录默认是应用私有目录，可通过
 * [ScriptStorageRepository] 切换到共享存储。脚本历史始终留在应用私有目录。
 */
class ScriptRepository(
    private val context: Context,
    private val storage: ScriptStorageRepository = ScriptStorageRepository(context),
    val history: ScriptHistoryRepository = ScriptHistoryRepository(context),
) : ScriptSource, ScriptModuleResolver {

    private val _revision = MutableStateFlow(0)

    /** 脚本集合 / 内容的变更信号。界面可借此在增删改（含编辑器保存）后重新拉取列表。 */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** 串行化对脚本文件的读写，避免订阅同步与编辑器保存互相踩踏。 */
    private val fileLock = Any()

    private fun bumpRevision() {
        _revision.value++
    }

    private val scriptsDir: File
        get() = storage.scriptsDir().apply { if (!exists()) mkdirs() }

    private val projectsDir: File
        get() = storage.projectsDir().apply { if (!exists()) mkdirs() }

    fun list(): List<ScriptInfo> = synchronized(fileLock) {
        val flat = scriptsDir
            .listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
            .orEmpty()
            .map { it.toScriptInfo() }
        val projects = projectsDir
            .listFiles { file -> file.isDirectory }
            .orEmpty()
            .mapNotNull { projectInfo(it) }
        (flat + projects).sortedByDescending { it.updatedAt }
    }

    override fun find(id: String): ScriptInfo? =
        list().firstOrNull { it.id == id } ?: findProjectFile(id)

    /** 项目内文件列表；入口排在最前。 */
    fun projectFiles(projectId: String): List<ScriptInfo> {
        val root = File(projectsDir, projectId)
        if (!root.isDirectory) return emptyList()
        val entry = projectInfo(root)?.relativePath
        return root.walkTopDown()
            .onEnter { dir -> dir.name !in SKIP_DIRECTORIES }
            .filter { file ->
                file.isFile && file.extension.lowercase() in PROJECT_EDITABLE_EXTENSIONS
            }
            .map { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                file.toProjectFileInfo(root, relative)
            }
            .sortedWith(compareBy({ it.relativePath != entry }, { it.relativePath }))
            .toList()
    }

    /**
     * 首次运行时把打包在 assets/scripts 下的内置脚本释放到脚本目录，供其他脚本 `require`。
     * 用标志位保证只做一次：用户删除内置脚本后不会再次被写回。
     */
    fun seedBundledScripts() {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        runCatching {
            context.assets.list(BUNDLED_DIR).orEmpty()
                .filter { it.endsWith(EXTENSION) }
                .forEach { fileName ->
                    val target = File(scriptsDir, fileName)
                    if (target.exists()) return@forEach
                    context.assets.open("$BUNDLED_DIR/$fileName").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
        }
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        bumpRevision()
    }

    fun create(name: String, content: String = DEFAULT_SCRIPT): ScriptInfo {
        val id = uniqueId(sanitize(name))
        val file = File(scriptsDir, id + EXTENSION)
        AtomicFile.writeText(file, content)
        bumpRevision()
        return file.toScriptInfo()
    }

    override fun read(script: ScriptInfo): String = synchronized(fileLock) {
        if (script.file.exists()) script.file.readText() else ""
    }

    fun write(
        script: ScriptInfo,
        content: String,
        forceHistory: Boolean = false,
        expectedSha256: String? = null,
    ) {
        synchronized(fileLock) {
            // 文件可能已被重命名 / 删除；不能静默成功，否则编辑器会以为已保存。
            check(script.file.exists()) { "脚本已不存在: ${script.id}" }
            val previous = script.file.readText()
            if (!expectedSha256.isNullOrBlank()) {
                val actual = sha256(previous)
                if (expectedSha256 != actual) {
                    throw ScriptStaleException(
                        "脚本内容已在别处修改，请重新读取后再编辑",
                        actualSha256 = actual,
                    )
                }
            }
            if (previous.isNotEmpty() && previous != content) {
                history.record(script.id, previous, force = forceHistory)
            }
            AtomicFile.writeText(script.file, content)
            bumpRevision()
        }
    }

    /**
     * 在 [fileLock] 内完成“读取 → 校验 → 变换 → 写回”，避免增量编辑与编辑器自动保存
     * 并发时丢失更新。只有内容真正变化时才写盘并留档。
     */
    fun update(
        script: ScriptInfo,
        expectedSha256: String? = null,
        transform: (String) -> String,
    ): ScriptUpdateResult = synchronized(fileLock) {
        if (!script.file.exists()) {
            throw IllegalStateException("脚本文件不存在：${script.id}")
        }
        val previous = script.file.readText()
        val previousHash = sha256(previous)
        if (!expectedSha256.isNullOrBlank() && expectedSha256 != previousHash) {
            throw ScriptStaleException(
                "脚本内容已在别处修改，请重新读取后再编辑",
                actualSha256 = previousHash,
            )
        }
        val updated = transform(previous)
        val changed = updated != previous
        if (changed) {
            history.record(script.id, previous, force = true)
            AtomicFile.writeText(script.file, updated)
            bumpRevision()
        }
        ScriptUpdateResult(
            // 内容更新不改变项目归属与相对路径，沿用调用方传入的元数据，避免项目
            // 脚本被降级成扁平脚本信息。
            script = script.copy(updatedAt = script.file.lastModified()),
            previousSha256 = previousHash,
            sha256 = sha256(updated),
            previousLines = lineCount(previous),
            lines = lineCount(updated),
            changed = changed,
        )
    }

    fun delete(script: ScriptInfo): Boolean = deleteAll(listOf(script)).isNotEmpty()

    /**
     * 批量删除：逐个清理文件与历史，但只发一次 revision。
     * 列表页以 revision 作为刷新信号，逐条删除会触发 N 次重扫导致闪烁。
     *
     * @return 实际删除成功（文件已不存在同样视为成功）的脚本 id。
     */
    fun deleteAll(scripts: List<ScriptInfo>): Set<String> {
        if (scripts.isEmpty()) return emptySet()
        return synchronized(fileLock) {
            val deleted = LinkedHashSet<String>()
            scripts.forEach { script ->
                val target = if (script.isProjectEntry && script.projectRoot != null) {
                    script.projectRoot
                } else {
                    script.file
                }
                // 文件已不存在也视为成功，避免重复删除时留下无主的标签 / 运行参数。
                if (target.deleteRecursively() || !target.exists()) {
                    deleted += script.id
                    history.deleteFor(script.id)
                }
            }
            if (deleted.isNotEmpty()) bumpRevision()
            deleted
        }
    }

    /** 当前脚本根目录；默认是 `context.filesDir`。 */
    fun storageRoot(): File = storage.root()

    fun storageSettings(): ScriptStorageSettings = storage.current()

    /**
     * 切换脚本根目录。复制是非破坏性的：目标已存在的文件跳过，旧目录保持不动。
     *
     * 调用方负责切到 IO 线程；[isRunning] 在文件锁内复核，避免“检查后到切换前”
     * 期间有新脚本启动。这里仍重新校验当前根目录，避免并发的两次切换把目录嵌套到一起。
     */
    fun switchStorageRoot(
        newRootPath: String?,
        copyExisting: Boolean,
        isRunning: () -> Boolean = { false },
    ): ScriptStorageSwitchResult {
        val useDefault = newRootPath.isNullOrBlank()
        val requestedRaw = if (useDefault) {
            context.filesDir
        } else {
            ScriptStoragePaths.normalizeRoot(newRootPath)
                ?: return ScriptStorageSwitchResult.InvalidPath
        }
        // filesDir 在不同厂商 ROM 上可能是符号链接，比较与保存前统一取 canonical。
        val requested = runCatching { requestedRaw.canonicalFile }.getOrNull() ?: requestedRaw
        return synchronized(fileLock) {
            if (isRunning()) return@synchronized ScriptStorageSwitchResult.Running
            val currentRoot = storage.root()
            val currentCanonical = runCatching { currentRoot.canonicalFile }
                .getOrNull() ?: currentRoot
            if (requested == currentCanonical) {
                // 旧版本或手工写入可能把默认目录存成了路径字符串，这里顺手清空。
                if (useDefault && !storage.current().isDefault) {
                    storage.saveRoot(null)
                    bumpRevision()
                }
                return@synchronized ScriptStorageSwitchResult.Unchanged
            }
            if (ScriptStoragePaths.isSameOrNested(requested, currentCanonical)) {
                return@synchronized ScriptStorageSwitchResult.NestedRoot
            }
            val targetScripts = ScriptStoragePaths.scriptsDir(requested)
            val targetProjects = ScriptStoragePaths.projectsDir(requested)
            if (!ensureWritableDir(requested) ||
                !ensureWritableDir(targetScripts) ||
                !ensureWritableDir(targetProjects)
            ) {
                return@synchronized ScriptStorageSwitchResult.NotWritable(requested.path)
            }
            val migration = if (copyExisting) {
                ScriptStorageMigration.copy(
                    sourceScripts = scriptsDir,
                    sourceProjects = projectsDir,
                    targetScripts = targetScripts,
                    targetProjects = targetProjects,
                )
            } else {
                ScriptStorageMigration.Result()
            }
            // 默认目录必须存 null，否则界面会把它当成自定义目录。
            storage.saveRoot(if (useDefault) null else requested.path)
            bumpRevision()
            ScriptStorageSwitchResult.Success(
                rootPath = requested.path,
                copied = migration.copied,
                skipped = migration.skipped,
                failed = migration.failed,
            )
        }
    }

    /** 建目录并实际写一个临时文件，避免只读挂载或权限不足被 `isDirectory` 漏判。 */
    private fun ensureWritableDir(dir: File): Boolean {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
        return runCatching {
            val probe = File.createTempFile(".izuki_write_", ".tmp", dir)
            probe.delete()
            true
        }.getOrDefault(false)
    }

    fun rename(script: ScriptInfo, newName: String): ScriptInfo = synchronized(fileLock) {
        val projectRoot = script.projectRoot
        if (projectRoot != null && script.isProjectEntry) {
            val base = sanitize(newName)
            val target = if (base == projectRoot.name) {
                projectRoot
            } else {
                File(projectsDir, uniqueProjectId(base))
            }
            if (target != projectRoot) {
                check(projectRoot.renameTo(target)) { "项目重命名失败: ${script.id}" }
            }
            updateProjectManifest(target, base, script.relativePath)
            history.move(script.id, target.name)
            bumpRevision()
            return@synchronized projectInfo(target)
                ?: throw IllegalStateException("项目重命名后无法读取入口: ${target.name}")
        }
        if (projectRoot != null) {
            // 项目内文件：保持在原目录内改名，避免误把文件移到扁平脚本目录。
            val parent = script.file.parentFile
                ?: throw IllegalStateException("项目文件缺少父目录: ${script.id}")
            val desired = sanitize(newName)
            val filename = if (desired.substringAfterLast('.', "").isEmpty()) {
                "$desired.${script.file.extension.ifBlank { "js" }}"
            } else {
                desired
            }
            val target = File(parent, filename)
            check(script.file.renameTo(target)) { "模块重命名失败: ${script.id}" }
            val relative = target.relativeTo(projectRoot).invariantSeparatorsPath
            history.move(script.id, "${projectRoot.name}/$relative")
            bumpRevision()
            return@synchronized target.toProjectFileInfo(projectRoot, relative)
        }
        val id = uniqueId(sanitize(newName))
        val target = File(scriptsDir, id + EXTENSION)
        script.file.renameTo(target)
        history.move(script.id, id)
        bumpRevision()
        target.toScriptInfo()
    }

    /**
     * 批量导出用条目：普通脚本为单个文件，项目脚本展开为完整目录树（含资源文件）。
     * 路径在这里统一生成，写入方只负责按字节复制。
     */
    fun archiveEntries(scripts: List<ScriptInfo>): List<ScriptArchiveEntry> =
        synchronized(fileLock) {
            val entries = ArrayList<ScriptArchiveEntry>()
            val usedNames = HashSet<String>()
            fun addEntry(name: String, file: File) {
                var candidate = name
                var index = 1
                while (!usedNames.add(candidate)) {
                    candidate = "${index}_$name"
                    index++
                }
                entries += ScriptArchiveEntry(candidate, file)
            }
            scripts.forEach { script ->
                val root = script.projectRoot
                if (script.isProjectEntry && root != null) {
                    val basePrefix = sanitize(script.name.ifBlank { root.name })
                    var prefix = basePrefix
                    var index = 1
                    while (usedNames.any { it == prefix || it.startsWith("$prefix/") }) {
                        prefix = "${basePrefix}_$index"
                        index++
                    }
                    root.walkTopDown()
                        .onEnter { it.name !in SKIP_DIRECTORIES }
                        .filter { it.isFile }
                        .forEach { file ->
                            val relative = file.relativeTo(root).invariantSeparatorsPath
                            addEntry("$prefix/$relative", file)
                        }
                } else if (root != null) {
                    addEntry(script.relativePath ?: script.name, script.file)
                } else {
                    val filename = if (script.name.endsWith(EXTENSION)) {
                        script.name
                    } else {
                        script.name + EXTENSION
                    }
                    addEntry(filename, script.file)
                }
            }
            entries
        }

    /** 后台线程版本，避免磁盘 IO 阻塞主线程 / 组合。 */
    suspend fun listAsync(): List<ScriptInfo> = withContext(Dispatchers.IO) { list() }

    suspend fun findAsync(id: String): ScriptInfo? = withContext(Dispatchers.IO) { find(id) }

    suspend fun readAsync(script: ScriptInfo): String = withContext(Dispatchers.IO) { read(script) }

    suspend fun createAsync(name: String, content: String = DEFAULT_SCRIPT): ScriptInfo =
        withContext(Dispatchers.IO) { create(name, content) }

    suspend fun writeAsync(
        script: ScriptInfo,
        content: String,
        forceHistory: Boolean = false,
        expectedSha256: String? = null,
    ) = withContext(Dispatchers.IO) { write(script, content, forceHistory, expectedSha256) }

    suspend fun deleteAsync(script: ScriptInfo): Boolean =
        withContext(Dispatchers.IO) { delete(script) }

    suspend fun deleteAllAsync(scripts: List<ScriptInfo>): Set<String> =
        withContext(Dispatchers.IO) { deleteAll(scripts) }

    suspend fun renameAsync(script: ScriptInfo, newName: String): ScriptInfo =
        withContext(Dispatchers.IO) { rename(script, newName) }

    /**
     * 导入 ZIP 项目。条目路径在这里做 zip-slip 防护，入口与项目根在解压后再识别；
     * 失败时清理临时目录，不会在 projects/ 下留下半成品。
     */
    fun importProjectZip(input: InputStream, displayName: String): ScriptInfo? {
        val staging = File(context.cacheDir, "project_import_${System.currentTimeMillis()}")
        if (!staging.mkdirs()) return null
        try {
            var total = 0L
            var count = 0
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++count > MAX_PROJECT_ENTRIES) return null
                    val name = entry.name.replace('\\', '/').trimStart('/')
                    if (name.isBlank() || name.startsWith("__MACOSX/")) continue
                    if (name.split('/').any { it == ".." }) continue
                    val target = safeChild(staging, name) ?: continue
                    if (entry.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    // entry.size 不可信时按实际字节数限制，避免单个条目先写满磁盘。
                    if (entry.size >= 0 && total + entry.size > MAX_PROJECT_BYTES) return null
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output ->
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_PROJECT_BYTES) return null
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }

            return synchronized(fileLock) {
                val root = detectProjectRoot(staging) ?: return@synchronized null
                val entry = detectProjectEntry(root) ?: return@synchronized null
                val name = displayName.substringBeforeLast('.').trim()
                    .ifBlank { root.name.ifBlank { "project" } }
                val id = uniqueProjectId(sanitize(name))
                val target = File(projectsDir, id)
                // 暂存在内部 cacheDir，外部脚本根目录与其不在同一挂载点时 renameTo 会失败。
                check(ScriptStorageMigration.move(root, target)) { "项目移动失败: $id" }
                // 与 CLI import 一致：入口没有标记时补上，运行时才会加载兼容层。
                ensureCompatHeader(safeChild(target, entry) ?: target)
                writeProjectManifestIfMissing(target, name, entry)
                bumpRevision()
                projectInfo(target)
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * 重命名 / 导入时更新项目清单。已有 `project.json` / `izuki.project.json` 时只改
     * name 与缺失的 entry，保留 compat 等其它字段。
     */
    private fun updateProjectManifest(root: File, name: String, entry: String?) {
        val file = listOf(PROJECT_MANIFEST, LEGACY_MANIFEST)
            .map { File(root, it) }
            .firstOrNull { it.isFile }
            ?: File(root, PROJECT_MANIFEST)
        val json = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        json.put("name", name)
        if (!json.has("entry") && !json.has("main") && !entry.isNullOrBlank()) {
            json.put("entry", entry)
        }
        AtomicFile.writeText(file, json.toString(2))
    }

    /** 兼容标记已存在时不做改动，避免每次导入都叠加一行。 */
    private fun ensureCompatHeader(file: File) {
        if (!file.isFile) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        if (text.contains("@compat")) return
        AtomicFile.writeText(file, COMPAT_HEADER + "\n" + text)
    }

    // ---- 项目解析 ----

    private fun projectInfo(root: File): ScriptInfo? {
        val manifest = readProjectManifest(root)
        val entry = manifest?.entry
            ?.takeIf { relative -> safeChild(root, relative)?.isFile == true }
            ?: detectDefaultEntry(root)
            ?: return null
        val file = safeChild(root, entry) ?: return null
        if (!file.isFile) return null
        return ScriptInfo(
            id = root.name,
            name = manifest?.name?.takeIf { it.isNotBlank() } ?: root.name,
            file = file,
            updatedAt = file.lastModified(),
            projectRoot = root,
            relativePath = file.relativeTo(root).invariantSeparatorsPath,
            isProjectEntry = true,
        )
    }

    private fun findProjectFile(id: String): ScriptInfo? {
        val slash = id.indexOf('/')
        if (slash <= 0) return null
        val root = File(projectsDir, id.substring(0, slash))
        if (!root.isDirectory) return null
        val relative = id.substring(slash + 1)
        val file = safeChild(root, relative) ?: return null
        if (!file.isFile) return null
        return file.toProjectFileInfo(root, file.relativeTo(root).invariantSeparatorsPath)
    }

    private fun File.toProjectFileInfo(root: File, relative: String): ScriptInfo =
        ScriptInfo(
            id = "${root.name}/$relative",
            name = relative,
            file = this,
            updatedAt = lastModified(),
            projectRoot = root,
            relativePath = relative,
            isProjectEntry = false,
        )

    private data class ProjectManifest(val name: String?, val entry: String?)

    private fun readProjectManifest(root: File): ProjectManifest? {
        for (name in listOf(PROJECT_MANIFEST, LEGACY_MANIFEST)) {
            val file = File(root, name)
            if (!file.isFile) continue
            val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: continue
            val entry = json.optString("entry").ifBlank { json.optString("main") }
                .ifBlank { null }
            val label = json.optString("name").ifBlank { null }
            return ProjectManifest(label, entry)
        }
        return null
    }

    private fun detectProjectRoot(staging: File): File? {
        if (File(staging, PROJECT_MANIFEST).isFile || File(staging, LEGACY_MANIFEST).isFile) {
            return staging
        }
        val children = staging.listFiles()
            ?.filter { it.isDirectory && it.name !in SKIP_DIRECTORIES }
            .orEmpty()
        return if (children.size == 1) children.first() else staging
    }

    private fun detectProjectEntry(root: File): String? {
        val manifestEntry = readProjectManifest(root)?.entry
        if (manifestEntry != null) {
            val file = safeChild(root, manifestEntry)
            if (file?.isFile == true) {
                return file.relativeTo(root).invariantSeparatorsPath
            }
        }
        return detectDefaultEntry(root)
    }

    private fun detectDefaultEntry(root: File): String? {
        for (name in listOf("main.js", "index.js", "app.js")) {
            if (File(root, name).isFile) return name
        }
        return root.walkTopDown()
            .onEnter { it.name !in SKIP_DIRECTORIES }
            .firstOrNull { it.isFile && it.extension.lowercase() in PROJECT_SCRIPT_EXTENSIONS }
            ?.relativeTo(root)
            ?.invariantSeparatorsPath
    }

    private fun writeProjectManifestIfMissing(root: File, name: String, entry: String) {
        if (File(root, PROJECT_MANIFEST).isFile || File(root, LEGACY_MANIFEST).isFile) return
        val json = JSONObject()
            .put("name", name)
            .put("entry", entry)
            .put("compat", "autojs6")
        AtomicFile.writeText(File(root, PROJECT_MANIFEST), json.toString(2))
    }

    private fun safeChild(root: File, relative: String): File? {
        val normalized = relative.replace('\\', '/').removePrefix("./").trimStart('/')
        if (normalized.isBlank() || normalized.split('/').any { it == ".." }) return null
        return runCatching {
            val canonicalRoot = root.canonicalFile
            val candidate = File(canonicalRoot, normalized).canonicalFile
            if (candidate == canonicalRoot ||
                !candidate.path.startsWith(canonicalRoot.path + File.separator)
            ) {
                null
            } else {
                candidate
            }
        }.getOrNull()
    }

    private fun uniqueProjectId(base: String): String {
        var candidate = base
        var index = 1
        while (File(projectsDir, candidate).exists() ||
            File(scriptsDir, candidate + EXTENSION).exists()
        ) {
            candidate = "${base}_$index"
            index++
        }
        return candidate
    }

    // ---- 模块解析 ----

    override fun resolve(name: String, from: String): String? {
        val fromFile = moduleFile(from)
        return ScriptModulePaths.resolve(name, fromFile, moduleRoot(fromFile), scriptsDir)
            ?.absolutePath
    }

    override fun source(name: String, from: String): String? =
        resolve(name, from)?.let { path ->
            runCatching { File(path).readText() }.getOrNull()
        }

    /**
     * 只做直接路径探测，不走 `list()` 全量扫描。
     *
     * `require` 发生在脚本线程上，原实现每次顶层 require 都会读取所有项目的 manifest
     * 并遍历目录；这里按 id 形状直接定位扁平脚本 / 项目入口 / 项目内文件。
     */
    private fun moduleFile(from: String): File? {
        if (from.isBlank()) return null
        val direct = File(from)
        if (direct.isAbsolute && direct.isFile) return direct
        val flat = File(scriptsDir, from.removeSuffix(EXTENSION) + EXTENSION)
        if (flat.isFile) return flat
        if (from.contains('/')) return findProjectFile(from)?.file
        val projectRoot = File(projectsDir, from)
        return if (projectRoot.isDirectory) projectInfo(projectRoot)?.file else null
    }

    private fun moduleRoot(fromFile: File?): File {
        val file = fromFile ?: return scriptsDir
        val projectsPath = runCatching { projectsDir.canonicalPath }.getOrNull()
            ?: return scriptsDir
        val filePath = runCatching { file.canonicalPath }.getOrNull() ?: return scriptsDir
        if (!filePath.startsWith(projectsPath + File.separator)) return scriptsDir
        val relative = filePath.substring(projectsPath.length + 1)
        val first = relative.substringBefore(File.separator)
        return if (first.isBlank()) scriptsDir else File(projectsDir, first)
    }

    private fun File.toScriptInfo(): ScriptInfo =
        ScriptInfo(
            id = nameWithoutExtension,
            name = nameWithoutExtension,
            file = this,
            updatedAt = lastModified(),
        )

    private fun sanitize(raw: String): String {
        val trimmed = raw.trim().ifBlank { "script" }
        return trimmed.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun uniqueId(base: String): String {
        var candidate = base
        var index = 1
        while (File(scriptsDir, candidate + EXTENSION).exists() ||
            File(projectsDir, candidate).exists()
        ) {
            candidate = "${base}_$index"
            index++
        }
        return candidate
    }

    companion object {
        const val EXTENSION = ".js"

        /** 内容 SHA-256（十六进制小写），用于增量编辑的乐观并发校验。 */
        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        /** 行数：空文本为 0；末尾换行会多计一个空行，与 `text.lines()` 语义一致。 */
        fun lineCount(text: String): Int =
            if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

        private const val BUNDLED_DIR = "scripts"
        private const val PREFS = "izukijs_scripts"
        private const val KEY_SEEDED = "bundled_seeded"
        private const val PROJECT_MANIFEST = "izuki.project.json"
        private const val LEGACY_MANIFEST = "project.json"

        /** 与 `runtime.CompatMode.HEADER` 保持一致；导入时写进入口脚本首行。 */
        private const val COMPAT_HEADER = "// @compat autojs6"
        private const val MAX_PROJECT_ENTRIES = 2_000
        private const val MAX_PROJECT_BYTES = 50L * 1024 * 1024
        private val SKIP_DIRECTORIES = ScriptStoragePaths.SKIP_DIRECTORIES
        private val PROJECT_EDITABLE_EXTENSIONS = setOf("js", "mjs", "cjs", "json")
        private val PROJECT_SCRIPT_EXTENSIONS = setOf("js", "mjs", "cjs")

        val DEFAULT_SCRIPT = """
            // Izuki JS 脚本示例
            toast("Hello, Izuki JS!");
            log("屏幕尺寸: " + device.width() + " x " + device.height());

            click(540, 1200);
            sleep(500);
            swipe(540, 1600, 540, 600, 300);
        """.trimIndent()
    }
}
