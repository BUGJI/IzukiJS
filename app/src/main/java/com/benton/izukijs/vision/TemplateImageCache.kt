package com.benton.izukijs.vision

import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.developer.DeveloperTuning
import java.io.File
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * 进程级模板缓存：`images.findImage*` 与统一识别共用，避免同一模板被解码两次。
 *
 * 调用方通过 [acquire] 批量租用模板，并在使用结束后调用 [release]。租用期间即使
 * 缓存被淘汰 / 清空，[Mat] 也不会被释放，避免多模板匹配时出现 use-after-free。
 * 容量从开发者选项读取，按访问顺序淘汰。
 */
internal object TemplateImageCache {

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    internal class Entry(val lastModified: Long, val mat: Mat) {
        var refs: Int = 0
        var retired: Boolean = false
    }

    /** 一次批量取用的租约；使用完必须调用 [release]。 */
    class Lease internal constructor(
        val mats: List<Mat>,
        internal val entries: List<Entry>,
    )

    fun acquire(paths: List<String>, logBus: LogBus, label: String = "模板"): Lease = synchronized(lock) {
        val mats = ArrayList<Mat>(paths.size)
        val leased = ArrayList<Entry>(paths.size)
        for (path in paths) {
            val entry = getOrLoad(path, logBus)
            if (entry == null) {
                logBus.warn("$label 模板不存在或无法读取：$path")
                continue
            }
            entry.refs++
            mats.add(entry.mat)
            leased.add(entry)
        }
        trim()
        Lease(mats, leased)
    }

    fun release(lease: Lease) {
        synchronized(lock) {
            for (entry in lease.entries) {
                entry.refs = (entry.refs - 1).coerceAtLeast(0)
                if (entry.refs == 0 && entry.retired) {
                    runCatching { entry.mat.release() }
                }
            }
            trim()
        }
    }

    fun clear() {
        synchronized(lock) {
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next().value
                iterator.remove()
                if (entry.refs == 0) {
                    runCatching { entry.mat.release() }
                } else {
                    entry.retired = true
                }
            }
        }
    }

    /** 淘汰未租用的最老条目；使用中的条目保留到 [release] 后再处理。 */
    private fun trim() {
        val maxEntries = DeveloperTuning.current.visionTemplateCacheEntries.coerceAtLeast(1)
        if (entries.size <= maxEntries) return
        val iterator = entries.entries.iterator()
        while (entries.size > maxEntries && iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.refs > 0) continue
            iterator.remove()
            runCatching { entry.mat.release() }
        }
    }

    private fun getOrLoad(path: String, logBus: LogBus): Entry? {
        val modified = File(path).lastModified()
        val existing = entries[path]
        if (existing != null) {
            if (existing.lastModified == modified) return existing
            entries.remove(path)
            if (existing.refs == 0) {
                runCatching { existing.mat.release() }
            } else {
                // 文件已变化但仍有调用方在使用旧 Mat：等租约释放后再回收。
                existing.retired = true
            }
        }

        val loaded = load(path, logBus) ?: return null
        return Entry(modified, loaded).also { entries[path] = it }
    }

    private fun load(path: String, logBus: LogBus): Mat? {
        var decoded: Mat? = null
        return try {
            decoded = Imgcodecs.imread(path, Imgcodecs.IMREAD_COLOR)
            if (decoded.empty()) {
                logBus.warn("模板解码为空：$path")
                null
            } else {
                Mat().also { Imgproc.cvtColor(decoded, it, Imgproc.COLOR_BGR2RGBA) }
            }
        } catch (t: Throwable) {
            logBus.error("模板加载失败 $path: ${t.message}")
            null
        } finally {
            runCatching { decoded?.release() }
        }
    }
}
