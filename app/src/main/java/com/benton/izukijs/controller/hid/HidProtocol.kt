package com.benton.izukijs.controller.hid

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * 蓝牙 HID 控制协议（App ⇄ 外部数位板狗）。多字节数值一律小端序。
 *
 * 详见 docs/BLE_HID_PROTOCOL.md。
 */
object HidProtocol {

    val SERVICE_UUID: UUID = UUID.fromString("7d8a0001-9a1e-4b2a-8f3c-1d2e3f4a5b6c")
    val CONTROL_UUID: UUID = UUID.fromString("7d8a0002-9a1e-4b2a-8f3c-1d2e3f4a5b6c")
    val EVENT_UUID: UUID = UUID.fromString("7d8a0003-9a1e-4b2a-8f3c-1d2e3f4a5b6c")

    const val PROTOCOL_VERSION = 2

    /** 固件从该版本起支持 CMD_GESTURE（复杂轨迹 / 停顿）。 */
    const val MIN_GESTURE_VERSION = 2

    // App -> 狗
    const val CMD_HANDSHAKE = 0x01
    const val CMD_SET_RESOLUTION = 0x02
    const val CMD_TAP = 0x03
    const val CMD_SWIPE = 0x04
    const val CMD_KEY = 0x05
    const val CMD_TEXT = 0x06
    const val CMD_PING = 0x07
    const val CMD_GESTURE = 0x08

    /** CMD_GESTURE flags：本帧结束后不抬手，供长轨迹拆帧续传。 */
    const val GESTURE_KEEP_DOWN = 0x01

    /** CMD_GESTURE flags：一条新轨迹的首帧，固件据此先抬手清除残留触点。 */
    const val GESTURE_START = 0x02

    /** 单帧最多轨迹点：1(cmd)+1(flags)+1(count)+6*n <= 260。 */
    const val MAX_GESTURE_POINTS = 42

    // 狗 -> App
    const val EVT_HANDSHAKE_ACK = 0x81
    const val EVT_ACK = 0x82
    const val EVT_STATUS = 0x83
    const val EVT_ERROR = 0x84
    const val EVT_PONG = 0x87

    // EVT_STATUS 载荷字节：就绪标志位（与固件 main/izuki_proto.h 保持一致）。
    const val STATUS_LINK_UP = 0x01
    const val STATUS_ENCRYPTED = 0x02
    const val STATUS_HID_READY = 0x04
    const val STATUS_APP_READY = 0x08

    // EVT_ERROR 载荷字节：错误码。
    const val ERR_HID_NOT_LINKED = 0x01
    const val ERR_HID_NOT_READY = 0x02
    const val ERR_INPUT_SET = 0x03

    private fun buffer(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    fun handshake(): ByteArray =
        byteArrayOf(CMD_HANDSHAKE.toByte(), PROTOCOL_VERSION.toByte())

    fun setResolution(width: Int, height: Int): ByteArray =
        buffer(5)
            .put(CMD_SET_RESOLUTION.toByte())
            .putShort(width.toShort())
            .putShort(height.toShort())
            .array()

    fun tap(x: Int, y: Int, durationMs: Int): ByteArray =
        buffer(7)
            .put(CMD_TAP.toByte())
            .putShort(x.toShort())
            .putShort(y.toShort())
            .putShort(durationMs.toShort())
            .array()

    fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
        steps: Int,
    ): ByteArray =
        buffer(12)
            .put(CMD_SWIPE.toByte())
            .putShort(x1.toShort())
            .putShort(y1.toShort())
            .putShort(x2.toShort())
            .putShort(y2.toShort())
            .putShort(durationMs.toShort())
            .put(steps.coerceIn(1, 255).toByte())
            .array()

    /** 轨迹中的一个采样点；[deltaMs] 表示从上一个点移动到本点所用的毫秒。 */
    data class GestureSample(val x: Int, val y: Int, val deltaMs: Int)

    /**
     * 复杂轨迹帧：`flags:u8, count:u8, count * {x:u16,y:u16,dt:u16}`。
     * 固件在相邻点之间线性插值，坐标不变的段即原地按住停顿。
     */
    fun gesture(flags: Int, points: List<GestureSample>): ByteArray {
        val count = points.size.coerceAtMost(MAX_GESTURE_POINTS)
        val buf = buffer(3 + count * 6)
        buf.put(CMD_GESTURE.toByte())
        buf.put(flags.toByte())
        buf.put(count.toByte())
        for (i in 0 until count) {
            val point = points[i]
            buf.putShort(point.x.coerceIn(0, 0xFFFF).toShort())
            buf.putShort(point.y.coerceIn(0, 0xFFFF).toShort())
            buf.putShort(point.deltaMs.coerceIn(0, 0xFFFF).toShort())
        }
        return buf.array()
    }

    fun key(usage: Int, modifier: Int, down: Boolean): ByteArray =
        byteArrayOf(
            CMD_KEY.toByte(),
            (usage and 0xFF).toByte(),
            (modifier and 0xFF).toByte(),
            if (down) 1 else 0,
        )

    fun text(bytes: ByteArray): ByteArray {
        val size = bytes.size.coerceAtMost(0xFFFF)
        return buffer(3 + size)
            .put(CMD_TEXT.toByte())
            .putShort(size.toShort())
            .put(bytes, 0, size)
            .array()
    }

    fun ping(): ByteArray = byteArrayOf(CMD_PING.toByte())

    data class HandshakeAck(val version: Int, val width: Int, val height: Int)

    data class Status(val flags: Int) {
        val linkUp: Boolean get() = (flags and STATUS_LINK_UP) != 0
        val encrypted: Boolean get() = (flags and STATUS_ENCRYPTED) != 0
        val hidReady: Boolean get() = (flags and STATUS_HID_READY) != 0
        val appReady: Boolean get() = (flags and STATUS_APP_READY) != 0
    }

    fun parseHandshakeAck(data: ByteArray): HandshakeAck? {
        if (data.size < 6 || (data[0].toInt() and 0xFF) != EVT_HANDSHAKE_ACK) return null
        val version = data[1].toInt() and 0xFF
        val width = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
        val height = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)
        return HandshakeAck(version, width, height)
    }

    fun parseStatus(data: ByteArray): Status? {
        if (data.size < 2 || (data[0].toInt() and 0xFF) != EVT_STATUS) return null
        return Status(flags = data[1].toInt() and 0xFF)
    }

    fun parseError(data: ByteArray): Int? {
        if (data.size < 2 || (data[0].toInt() and 0xFF) != EVT_ERROR) return null
        return data[1].toInt() and 0xFF
    }

    fun errorText(code: Int): String = when (code) {
        ERR_HID_NOT_LINKED -> "固件未连上 HID 主机"
        ERR_HID_NOT_READY -> "HID 链路未就绪（未加密）"
        ERR_INPUT_SET -> "固件注入 HID 报表失败"
        else -> "未知错误 0x%02x".format(code)
    }

    fun kind(data: ByteArray): Int = if (data.isEmpty()) -1 else data[0].toInt() and 0xFF
}
