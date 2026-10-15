package com.benton.izukijs.runtime.api

import android.util.Base64
import android.webkit.JavascriptInterface
import com.benton.izukijs.runtime.LogBus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 文件 API。挂在全局 `fs` 命名空间。
 *
 * 访问共享存储中的任意路径需要「全部文件访问」权限（MANAGE_EXTERNAL_STORAGE / 旧版
 * WRITE_EXTERNAL_STORAGE）；未授权时对受限路径的操作会失败并返回 null / false。
 * 所有方法都捕获异常，绝不向 QuickJS 抛 Java 异常。
 */
class FileApi(private val logBus: LogBus) {

    @JavascriptInterface
    fun readText(path: String): String? = runCatching {
        File(path).readText()
    }.onFailure { logBus.warn("fs.readText 失败: ${it.message}") }.getOrNull()

    @JavascriptInterface
    fun writeText(path: String, text: String, append: Boolean): Boolean = runCatching {
        val file = File(path)
        file.parentFile?.mkdirs()
        if (append) file.appendText(text) else file.writeText(text)
        true
    }.onFailure { logBus.warn("fs.writeText 失败: ${it.message}") }.getOrDefault(false)

    @JavascriptInterface
    fun exists(path: String): Boolean = runCatching { File(path).exists() }.getOrDefault(false)

    @JavascriptInterface
    fun isDirectory(path: String): Boolean = runCatching { File(path).isDirectory }.getOrDefault(false)

    @JavascriptInterface
    fun size(path: String): Double = runCatching { File(path).length().toDouble() }.getOrDefault(-1.0)

    @JavascriptInterface
    fun mkdirs(path: String): Boolean = runCatching { File(path).mkdirs() || File(path).exists() }
        .getOrDefault(false)

    @JavascriptInterface
    fun delete(path: String): Boolean = runCatching { File(path).delete() }.getOrDefault(false)

    @JavascriptInterface
    fun copy(src: String, dst: String): Boolean = runCatching {
        val target = File(dst)
        target.parentFile?.mkdirs()
        File(src).copyTo(target, overwrite = true)
        true
    }.onFailure { logBus.warn("fs.copy 失败: ${it.message}") }.getOrDefault(false)

    @JavascriptInterface
    fun move(src: String, dst: String): Boolean = runCatching {
        val target = File(dst)
        target.parentFile?.mkdirs()
        val source = File(src)
        if (source.renameTo(target)) true else {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }.onFailure { logBus.warn("fs.move 失败: ${it.message}") }.getOrDefault(false)

    /** 列出目录下的条目，返回 JSON 数组：[{name,path,dir,size}]；失败或非目录返回 null。 */
    @JavascriptInterface
    fun list(path: String): String? = runCatching {
        val dir = File(path)
        if (!dir.isDirectory) return@runCatching null
        val array = JSONArray()
        dir.listFiles().orEmpty().forEach { file ->
            array.put(
                JSONObject().apply {
                    put("name", file.name)
                    put("path", file.absolutePath)
                    put("dir", file.isDirectory)
                    put("size", file.length())
                },
            )
        }
        array.toString()
    }.onFailure { logBus.warn("fs.list 失败: ${it.message}") }.getOrNull()

    @JavascriptInterface
    fun readBase64(path: String): String? = runCatching {
        Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP)
    }.onFailure { logBus.warn("fs.readBase64 失败: ${it.message}") }.getOrNull()

    @JavascriptInterface
    fun writeBase64(path: String, base64: String): Boolean = runCatching {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(Base64.decode(base64, Base64.DEFAULT))
        true
    }.onFailure { logBus.warn("fs.writeBase64 失败: ${it.message}") }.getOrDefault(false)
}
