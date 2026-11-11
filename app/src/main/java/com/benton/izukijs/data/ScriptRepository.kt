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
import java.security.MessageDigest

/** 脚本内容在一次原子更新中的前后状态。 */
data class ScriptUpdateResult(
    val script: ScriptInfo,
    val previousSha256: String,
    val sha256: String,
    val previousLines: Int,
    val lines: Int,
    val changed: Boolean,
)

/** `expectedSha256` 与当前内容不一致：说明脚本在读取后被其他人改过。 */
class ScriptStaleException(
    message: String,
    val actualSha256: String,
) : IllegalStateException(message)

/**
 * 基于应用私有目录的脚本仓库。脚本以 .js 文件形式保存在 files/scripts 下。
 */
class ScriptRepository(
    private val context: Context,
    val history: ScriptHistoryRepository = ScriptHistoryRepository(context),
) {

    private val _revision = MutableStateFlow(0)

    /** 脚本集合 / 内容的变更信号。界面可借此在增删改（含编辑器保存）后重新拉取列表。 */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** 串行化对脚本文件的读写，避免订阅同步与编辑器保存互相踩踏。 */
    private val fileLock = Any()

    private fun bumpRevision() {
        _revision.value++
    }

    private val scriptsDir: File
        get() = File(context.filesDir, "scripts").apply { if (!exists()) mkdirs() }

    fun list(): List<ScriptInfo> = scriptsDir
        .listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }
        .orEmpty()
        .sortedByDescending { it.lastModified() }
        .map { it.toScriptInfo() }

    fun find(id: String): ScriptInfo? = list().firstOrNull { it.id == id }

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

    fun read(script: ScriptInfo): String = synchronized(fileLock) {
        if (script.file.exists()) script.file.readText() else ""
    }

    fun write(script: ScriptInfo, content: String, forceHistory: Boolean = false) {
        synchronized(fileLock) {
            if (!script.file.exists()) return
            val previous = runCatching {
                if (script.file.exists()) script.file.readText() else null
            }.getOrNull()
            if (!previous.isNullOrEmpty() && previous != content) {
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
            script = script.file.toScriptInfo(),
            previousSha256 = previousHash,
            sha256 = sha256(updated),
            previousLines = lineCount(previous),
            lines = lineCount(updated),
            changed = changed,
        )
    }

    fun delete(script: ScriptInfo) {
        script.file.delete()
        history.deleteFor(script.id)
        bumpRevision()
    }

    fun rename(script: ScriptInfo, newName: String): ScriptInfo {
        val id = uniqueId(sanitize(newName))
        val target = File(scriptsDir, id + EXTENSION)
        script.file.renameTo(target)
        history.move(script.id, id)
        bumpRevision()
        return target.toScriptInfo()
    }

    /** 后台线程版本，避免磁盘 IO 阻塞主线程 / 组合。 */
    suspend fun listAsync(): List<ScriptInfo> = withContext(Dispatchers.IO) { list() }

    suspend fun findAsync(id: String): ScriptInfo? = withContext(Dispatchers.IO) { find(id) }

    suspend fun readAsync(script: ScriptInfo): String = withContext(Dispatchers.IO) { read(script) }

    suspend fun createAsync(name: String, content: String = DEFAULT_SCRIPT): ScriptInfo =
        withContext(Dispatchers.IO) { create(name, content) }

    suspend fun writeAsync(script: ScriptInfo, content: String, forceHistory: Boolean = false) =
        withContext(Dispatchers.IO) { write(script, content, forceHistory) }

    suspend fun deleteAsync(script: ScriptInfo) = withContext(Dispatchers.IO) { delete(script) }

    suspend fun renameAsync(script: ScriptInfo, newName: String): ScriptInfo =
        withContext(Dispatchers.IO) { rename(script, newName) }

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
        while (File(scriptsDir, candidate + EXTENSION).exists()) {
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
