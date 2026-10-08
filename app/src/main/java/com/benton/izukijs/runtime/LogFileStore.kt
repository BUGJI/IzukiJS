package com.benton.izukijs.runtime

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "izuki-log-io").apply { isDaemon = true }
    }

    /** 待写入的日志按窗口聚合，减少打开/写入文件的次数与自动清理时的 stat 调用。 */
    private val pending = StringBuilder()
    private var flushScheduled = false

    fun append(entry: LogEntry) {
        val schedule: Boolean
        synchronized(lock) {
            pending.append(entry.timeMillis)
                .append('|').append(entry.level.name).append('|')
                .append(entry.message).append('\n')
            schedule = !flushScheduled
            if (schedule) flushScheduled = true
        }
        if (schedule) {
            scheduler.schedule(
                { synchronized(lock) { flushLocked() } },
                FLUSH_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    fun sizeBytes(): Long = if (logFile.exists()) logFile.length() else 0L

    /** 将当前日志复制到 [destination]，用于分享/导出。 */
    fun exportTo(destination: File): Boolean = synchronized(lock) {
        flushLocked()
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
        synchronized(lock) {
            pending.setLength(0)
            flushScheduled = false
            runCatching { logFile.writeText("") }
        }
    }

    /** 主动清理（启动或进入设置页时调用）。 */
    fun trim() {
        synchronized(lock) {
            flushLocked()
            val settings = settingsRepository.current()
            runCatching {
                enforceSizeLocked(settings)
                enforceRetentionLocked(settings)
            }
        }
    }

    private fun flushLocked() {
        flushScheduled = false
        if (pending.isEmpty()) return
        val data = pending.toString().toByteArray()
        pending.setLength(0)
        runCatching {
            logDir.mkdirs()
            FileOutputStream(logFile, true).use { it.write(data) }
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
        val temp = File(logDir, "${logFile.name}.retention.tmp")
        var removed = false
        runCatching {
            // 逐行过滤写入临时文件，避免把整个日志读入内存后再整体重写。
            BufferedReader(InputStreamReader(FileInputStream(logFile), Charsets.UTF_8)).use { reader ->
                BufferedWriter(OutputStreamWriter(FileOutputStream(temp), Charsets.UTF_8)).use { writer ->
                    var line = reader.readLine()
                    while (line != null) {
                        val timestamp = line.substringBefore('|').toLongOrNull()
                        if (timestamp == null || timestamp >= cutoff) {
                            writer.write(line)
                            writer.newLine()
                        } else {
                            removed = true
                        }
                        line = reader.readLine()
                    }
                }
            }
            if (removed) {
                logFile.delete()
                if (!temp.renameTo(logFile)) temp.delete()
            } else {
                temp.delete()
            }
        }.onFailure { temp.delete() }
    }

    private companion object {
        const val FLUSH_INTERVAL_MS = 200L
    }
}
