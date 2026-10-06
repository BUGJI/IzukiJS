package com.benton.izukijs.runtime

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.Executors

/**
 * 日志文件存储。支持按最大体积与保留天数自动清理。
 *
 * 所有磁盘写入都在单独的后台线程串行执行，避免阻塞脚本线程 / UI 线程。
 */
class LogFileStore(
    context: Context,
    private val settingsRepository: LogSettingsRepository,
) {

    private val logDir = File(context.filesDir, "logs")
    private val logFile = File(logDir, "app.log")
    private val lock = Any()

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "izuki-log-io").apply { isDaemon = true }
    }

    fun append(entry: LogEntry) {
        executor.execute { appendLocked(entry) }
    }

    fun sizeBytes(): Long = if (logFile.exists()) logFile.length() else 0L

    /** 将当前日志复制到 [destination]，用于分享/导出。 */
    fun exportTo(destination: File): Boolean = synchronized(lock) {
        runCatching {
            destination.parentFile?.mkdirs()
            if (logFile.exists()) {
                logFile.copyTo(destination, overwrite = true)
            } else {
                destination.writeText("")
            }
            true
        }.getOrDefault(false)
    }

    fun clear() {
        executor.execute {
            synchronized(lock) { runCatching { logFile.writeText("") } }
        }
    }

    /** 主动清理（启动或进入设置页时调用）。 */
    fun trim() {
        executor.execute {
            synchronized(lock) {
                val settings = settingsRepository.current()
                runCatching {
                    enforceSizeLocked(settings)
                    enforceRetentionLocked(settings)
                }
            }
        }
    }

    private fun appendLocked(entry: LogEntry) = synchronized(lock) {
        runCatching {
            logDir.mkdirs()
            logFile.appendText("${entry.timeMillis}|${entry.level.name}|${entry.message}\n")
            val settings = settingsRepository.current()
            if (settings.autoClean) enforceSizeLocked(settings)
        }
    }

    /** 超过上限时保留尾部约一半内容，采用流式截断，避免整文件读入内存。 */
    private fun enforceSizeLocked(settings: LogSettings) {
        val maxBytes = settings.maxSizeMb.toLong() * 1024L * 1024L
        if (maxBytes <= 0 || !logFile.exists() || logFile.length() <= maxBytes) return
        val keep = (maxBytes / 2).coerceAtMost(logFile.length())
        val temp = File(logDir, "${logFile.name}.tmp")
        RandomAccessFile(logFile, "r").use { source ->
            val start = (source.length() - keep).coerceAtLeast(0L)
            source.seek(start)
            if (start > 0L) {
                var b = source.read()
                while (b != -1 && b != '\n'.code) b = source.read()
            }
            FileOutputStream(temp).use { out ->
                val buffer = ByteArray(8192)
                var read = source.read(buffer)
                while (read > 0) {
                    out.write(buffer, 0, read)
                    read = source.read(buffer)
                }
            }
        }
        logFile.delete()
        if (!temp.renameTo(logFile)) temp.delete()
    }

    private fun enforceRetentionLocked(settings: LogSettings) {
        if (settings.retentionDays <= 0 || !logFile.exists()) return
        val cutoff = System.currentTimeMillis() -
            settings.retentionDays.toLong() * 24L * 60L * 60L * 1000L
        val kept = logFile.readLines().filter { line ->
            val timestamp = line.substringBefore('|').toLongOrNull()
            timestamp == null || timestamp >= cutoff
        }
        logFile.writeText(kept.joinToString("\n"))
    }
}
