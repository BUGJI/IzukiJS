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

    const val PROTOCOL_VERSION = 1

    // App -> 狗
    const val CMD_HANDSHAKE = 0x01
    const val CMD_SET_RESOLUTION = 0x02
    const val CMD_TAP = 0x03
    const val CMD_SWIPE = 0x04
    const val CMD_KEY = 0x05
    const val CMD_TEXT = 0x06
    const val CMD_PING = 0x07

    // 狗 -> App
    const val EVT_HANDSHAKE_ACK = 0x81
    const val EVT_ACK = 0x82
    const val EVT_STATUS = 0x83
    const val EVT_ERROR = 0x84
    const val EVT_PONG = 0x87

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

    data class Status(val battery: Int, val mode: Int)

    fun parseHandshakeAck(data: ByteArray): HandshakeAck? {
        if (data.size < 6 || (data[0].toInt() and 0xFF) != EVT_HANDSHAKE_ACK) return null
        val version = data[1].toInt() and 0xFF
        val width = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
        val height = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)
        return HandshakeAck(version, width, height)
    }

    fun parseStatus(data: ByteArray): Status? {
        if (data.size < 3 || (data[0].toInt() and 0xFF) != EVT_STATUS) return null
        return Status(battery = data[1].toInt() and 0xFF, mode = data[2].toInt() and 0xFF)
    }

    fun kind(data: ByteArray): Int = if (data.isEmpty()) -1 else data[0].toInt() and 0xFF
}
