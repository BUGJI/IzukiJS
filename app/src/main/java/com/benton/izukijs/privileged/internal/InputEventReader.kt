package com.benton.izukijs.privileged.internal

import android.os.Process
import android.os.SystemClock
import java.io.Closeable
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 直读 /dev/input/eventN 的 struct input_event。
 *
 * 64 位进程固定 24 字节（timeval 16 + type 2 + code 2 + value 4），32 位为 16 字节。
 * 这里不依赖文本 getevent，避免不同 toybox 版本的行格式差异。
 */
internal class InputEventReader(
    private val stream: InputStream,
    private val eventSize: Int,
    private val nowMs: () -> Long = { SystemClock.uptimeMillis() },
    private val nowWallMs: () -> Long = { System.currentTimeMillis() },
) : Closeable {

    fun interface Sink {
        fun onEvent(type: Int, code: Int, value: Int, atMs: Long)
    }

    fun readLoop(sink: Sink) {
        val buffer = ByteArray(eventSize * BATCH)
        val view = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        var pending = 0
        while (true) {
            val read = stream.read(buffer, pending, buffer.size - pending)
            if (read <= 0) return
            val total = pending + read
            val now = nowMs()
            val nowWall = nowWallMs()
            var offset = 0
            while (total - offset >= eventSize) {
                val typeOffset = if (eventSize == 24) 16 else 8
                val type = view.getShort(offset + typeOffset).toInt() and 0xFFFF
                val code = view.getShort(offset + typeOffset + 2).toInt() and 0xFFFF
                val value = view.getInt(offset + typeOffset + 4)
                val eventMs = readEventMs(view, offset, now, nowWall) ?: now
                sink.onEvent(type, code, value, eventMs)
                offset += eventSize
            }
            pending = total - offset
            if (pending > 0) System.arraycopy(buffer, offset, buffer, 0, pending)
        }
    }

    /**
     * 读取 struct input_event 的 timeval。
     *
     * 不同设备/内核的 evdev 时间基准不一致：多数是 CLOCK_MONOTONIC（与
     * uptimeMillis 同基准），部分设备是 CLOCK_REALTIME（epoch 秒）。
     * 这里按数值大小判断：明显是 epoch 时间就换算成"距现在的 uptime 毫秒"。
     */
    private fun readEventMs(
        view: ByteBuffer,
        offset: Int,
        nowUptime: Long,
        nowWall: Long,
    ): Long? {
        val (seconds, micros) = if (eventSize == 24) {
            view.getLong(offset) to view.getLong(offset + 8)
        } else {
            (view.getInt(offset).toLong() and 0xFFFFFFFFL) to
                (view.getInt(offset + 4).toLong() and 0xFFFFFFFFL)
        }
        if (seconds <= 0L) return null
        val rawMs = seconds * 1000L + micros / 1000L
        if (rawMs <= 0L) return null
        if (rawMs >= REALTIME_THRESHOLD_MS) {
            val age = (nowWall - rawMs).coerceAtLeast(0L)
            return (nowUptime - age).coerceAtLeast(0L)
        }
        return rawMs
    }

    override fun close() {
        runCatching { stream.close() }
    }

    companion object {
        private const val BATCH = 64

        /** 2001-09-09 之后的时间戳视为 epoch 实时时钟，而不是开机以来的单调时钟。 */
        private const val REALTIME_THRESHOLD_MS = 1_000_000_000_000L

        fun open(path: String): InputEventReader? = runCatching {
            val eventSize = if (Process.is64Bit()) 24 else 16
            InputEventReader(FileInputStream(path), eventSize)
        }.onFailure {
            PrivilegedLog.w("open $path failed: ${it.message}")
        }.getOrNull()
    }
}
