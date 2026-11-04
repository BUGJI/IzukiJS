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
            var offset = 0
            while (total - offset >= eventSize) {
                val type = view.getShort(offset + OFFSET_TYPE).toInt() and 0xFFFF
                val code = view.getShort(offset + OFFSET_CODE).toInt() and 0xFFFF
                val value = view.getInt(offset + OFFSET_VALUE)
                sink.onEvent(type, code, value, now)
                offset += eventSize
            }
            pending = total - offset
            if (pending > 0) System.arraycopy(buffer, offset, buffer, 0, pending)
        }
    }

    override fun close() {
        runCatching { stream.close() }
    }

    companion object {
        const val OFFSET_TYPE = 16
        const val OFFSET_CODE = 18
        const val OFFSET_VALUE = 20
        private const val BATCH = 64

        fun open(path: String): InputEventReader? = runCatching {
            val eventSize = if (Process.is64Bit()) 24 else 16
            InputEventReader(FileInputStream(path), eventSize)
        }.onFailure {
            PrivilegedLog.w("open $path failed: ${it.message}")
        }.getOrNull()
    }
}
