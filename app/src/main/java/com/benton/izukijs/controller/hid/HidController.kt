package com.benton.izukijs.controller.hid

import android.view.KeyEvent
import com.benton.izukijs.controller.DeviceController
import com.benton.izukijs.controller.GestureStroke
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode

/**
 * 蓝牙 HID 控制后端。手机作 HID 主机，通过外部数位板狗以绝对坐标注入输入。
 *
 * 能力：绝对坐标指针、单指手势、键盘、文本。无控件树/截图/Shell。
 */
class HidController(
    private val client: HidGattClient,
) : DeviceController {

    override val mode: ControlMode = ControlMode.HID

    private val cachedCapabilities: Set<Capability> = setOf(
        Capability.ABSOLUTE_POINTER,
        Capability.GESTURE,
        Capability.KEY,
        Capability.TEXT,
    )

    override fun capabilities(): Set<Capability> = cachedCapabilities

    override fun isReady(): Boolean = client.isReady

    override fun click(x: Float, y: Float): Boolean =
        client.send(HidProtocol.tap(x.toInt(), y.toInt(), TAP_DURATION_MS))

    override fun longClick(x: Float, y: Float, durationMs: Long): Boolean =
        client.send(HidProtocol.tap(x.toInt(), y.toInt(), durationMs.toInt()))

    override fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
    ): Boolean = client.send(
        HidProtocol.swipe(
            x1.toInt(),
            y1.toInt(),
            x2.toInt(),
            y2.toInt(),
            durationMs.toInt(),
            steps = (durationMs / 16L).toInt().coerceIn(2, 120),
        ),
    )

    override fun pressKey(keyCode: Int): Boolean {
        val usage = keyUsage(keyCode)
        if (usage == null) return false
        client.send(HidProtocol.key(usage, 0, true))
        client.send(HidProtocol.key(usage, 0, false))
        return true
    }

    override fun inputText(text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return true
        var sent = false
        var offset = 0
        while (offset < bytes.size) {
            val end = alignToUtf8Boundary(bytes, minOf(offset + MAX_TEXT_CHUNK_BYTES, bytes.size))
            if (!client.send(HidProtocol.text(bytes.copyOfRange(offset, end)))) return sent
            sent = true
            offset = end
        }
        return sent
    }

    /** 回退到 UTF-8 起始字节，避免把一个多字节字符拆到两帧。 */
    private fun alignToUtf8Boundary(bytes: ByteArray, end: Int): Int {
        if (end >= bytes.size) return bytes.size
        var boundary = end
        while (boundary > 0 && (bytes[boundary].toInt() and 0xC0) == 0x80) boundary--
        return if (boundary > 0) boundary else end
    }

    override fun gesture(strokes: List<GestureStroke>): Boolean = false

    private fun keyUsage(keyCode: Int): Int? = when {
        keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> HID_USAGE_A + (keyCode - KeyEvent.KEYCODE_A)
        keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> HID_USAGE_1 + (keyCode - KeyEvent.KEYCODE_1)
        keyCode == KeyEvent.KEYCODE_0 -> HID_USAGE_0
        keyCode == KeyEvent.KEYCODE_ENTER -> HID_USAGE_ENTER
        keyCode == KeyEvent.KEYCODE_ESCAPE -> HID_USAGE_ESCAPE
        keyCode == KeyEvent.KEYCODE_DEL -> HID_USAGE_BACKSPACE
        keyCode == KeyEvent.KEYCODE_TAB -> HID_USAGE_TAB
        keyCode == KeyEvent.KEYCODE_SPACE -> HID_USAGE_SPACE
        keyCode == KeyEvent.KEYCODE_FORWARD_DEL -> HID_USAGE_DELETE
        keyCode == KeyEvent.KEYCODE_DPAD_UP -> HID_USAGE_UP
        keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> HID_USAGE_DOWN
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> HID_USAGE_LEFT
        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> HID_USAGE_RIGHT
        else -> null
    }

    private companion object {
        const val TAP_DURATION_MS = 50

        /** 单帧文本上限（字节）。预留 MTU 余量，同时避免协议 u16 长度回绕。 */
        const val MAX_TEXT_CHUNK_BYTES = 512

        const val HID_USAGE_A = 0x04
        const val HID_USAGE_1 = 0x1E
        const val HID_USAGE_0 = 0x27
        const val HID_USAGE_ENTER = 0x28
        const val HID_USAGE_ESCAPE = 0x29
        const val HID_USAGE_BACKSPACE = 0x2A
        const val HID_USAGE_TAB = 0x2B
        const val HID_USAGE_SPACE = 0x2C
        const val HID_USAGE_DELETE = 0x4C
        const val HID_USAGE_RIGHT = 0x4F
        const val HID_USAGE_LEFT = 0x50
        const val HID_USAGE_DOWN = 0x51
        const val HID_USAGE_UP = 0x52
    }
}
