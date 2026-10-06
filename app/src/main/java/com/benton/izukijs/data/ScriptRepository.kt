package com.benton.izukijs.data

import android.content.Context
import com.benton.izukijs.model.ScriptInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 基于应用私有目录的脚本仓库。脚本以 .js 文件形式保存在 files/scripts 下。
 */
class ScriptRepository(private val context: Context) {

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
    }

    fun create(name: String, content: String = DEFAULT_SCRIPT): ScriptInfo {
        val id = uniqueId(sanitize(name))
        val file = File(scriptsDir, id + EXTENSION)
        file.writeText(content)
        return file.toScriptInfo()
    }

    fun read(script: ScriptInfo): String =
        if (script.file.exists()) script.file.readText() else ""

    fun write(script: ScriptInfo, content: String) {
        script.file.writeText(content)
    }

    fun delete(script: ScriptInfo) {
        script.file.delete()
    }

    fun rename(script: ScriptInfo, newName: String): ScriptInfo {
        val id = uniqueId(sanitize(newName))
        val target = File(scriptsDir, id + EXTENSION)
        script.file.renameTo(target)
        return target.toScriptInfo()
    }

    /** 后台线程版本，避免磁盘 IO 阻塞主线程 / 组合。 */
    suspend fun listAsync(): List<ScriptInfo> = withContext(Dispatchers.IO) { list() }

    suspend fun findAsync(id: String): ScriptInfo? = withContext(Dispatchers.IO) { find(id) }

    suspend fun readAsync(script: ScriptInfo): String = withContext(Dispatchers.IO) { read(script) }

    suspend fun createAsync(name: String, content: String = DEFAULT_SCRIPT): ScriptInfo =
        withContext(Dispatchers.IO) { create(name, content) }

    suspend fun writeAsync(script: ScriptInfo, content: String) =
        withContext(Dispatchers.IO) { write(script, content) }

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
