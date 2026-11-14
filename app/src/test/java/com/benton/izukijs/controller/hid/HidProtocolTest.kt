package com.benton.izukijs.controller.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HidProtocolTest {

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

    private fun ByteArray.u16(index: Int): Int =
        u8(index) or (u8(index + 1) shl 8)

    @Test
    fun handshakeCarriesVersion() {
        assertArrayEquals(
            byteArrayOf(HidProtocol.CMD_HANDSHAKE.toByte(), HidProtocol.PROTOCOL_VERSION.toByte()),
            HidProtocol.handshake(),
        )
    }

    @Test
    fun setResolutionUsesLittleEndian() {
        val payload = HidProtocol.setResolution(1080, 2400)

        assertEquals(5, payload.size)
        assertEquals(HidProtocol.CMD_SET_RESOLUTION, payload.u8(0))
        assertEquals(1080, payload.u16(1))
        assertEquals(2400, payload.u16(3))
    }

    @Test
    fun tapEncodesCoordinatesAndDuration() {
        val payload = HidProtocol.tap(100, 200, 50)

        assertEquals(7, payload.size)
        assertEquals(HidProtocol.CMD_TAP, payload.u8(0))
        assertEquals(100, payload.u16(1))
        assertEquals(200, payload.u16(3))
        assertEquals(50, payload.u16(5))
    }

    @Test
    fun swipeEncodesEndpointsAndClampsSteps() {
        val payload = HidProtocol.swipe(1, 2, 3, 4, 500, steps = 0)
        assertEquals(12, payload.size)
        assertEquals(HidProtocol.CMD_SWIPE, payload.u8(0))
        assertEquals(1, payload.u16(1))
        assertEquals(2, payload.u16(3))
        assertEquals(3, payload.u16(5))
        assertEquals(4, payload.u16(7))
        assertEquals(500, payload.u16(9))
        assertEquals(1, payload.u8(11))

        assertEquals(255, HidProtocol.swipe(0, 0, 0, 0, 0, steps = 300).u8(11))
    }

    @Test
    fun gestureEncodesPointsAndFlags() {
        val payload = HidProtocol.gesture(
            flags = HidProtocol.GESTURE_START or HidProtocol.GESTURE_KEEP_DOWN,
            points = listOf(
                HidProtocol.GestureSample(x = 0x1234, y = 0x5678, deltaMs = 10),
                HidProtocol.GestureSample(x = 1, y = 2, deltaMs = 3),
            ),
        )

        assertEquals(HidProtocol.CMD_GESTURE, payload.u8(0))
        assertEquals(0x03, payload.u8(1))
        assertEquals(2, payload.u8(2))
        assertEquals(15, payload.size)
        assertEquals(0x1234, payload.u16(3))
        assertEquals(0x5678, payload.u16(5))
        assertEquals(10, payload.u16(7))
        assertEquals(1, payload.u16(9))
        assertEquals(2, payload.u16(11))
        assertEquals(3, payload.u16(13))
    }

    @Test
    fun gestureClampsPointCountAndValues() {
        val points = List(60) { HidProtocol.GestureSample(x = -5, y = 70_000, deltaMs = -1) }
        val payload = HidProtocol.gesture(flags = 0, points = points)

        assertEquals(HidProtocol.MAX_GESTURE_POINTS, payload.u8(2))
        assertEquals(0, payload.u16(3))
        assertEquals(0xFFFF, payload.u16(5))
        assertEquals(0, payload.u16(7))
        assertEquals(3 + HidProtocol.MAX_GESTURE_POINTS * 6, payload.size)
    }

    @Test
    fun keyMasksUsageAndModifier() {
        val payload = HidProtocol.key(usage = 0x1_04, modifier = 0x1_02, down = true)

        assertArrayEquals(
            byteArrayOf(
                HidProtocol.CMD_KEY.toByte(),
                0x04,
                0x02,
                1,
            ),
            payload,
        )
        assertEquals(0, HidProtocol.key(4, 2, down = false).u8(3))
    }

    @Test
    fun textEncodesLengthPrefixAndTruncates() {
        val payload = HidProtocol.text("hi".toByteArray())

        assertEquals(HidProtocol.CMD_TEXT, payload.u8(0))
        assertEquals(2, payload.u16(1))
        assertEquals(5, payload.size)
        assertEquals('h'.code.toByte(), payload[3])
        assertEquals('i'.code.toByte(), payload[4])

        val huge = HidProtocol.text(ByteArray(70_000))
        assertEquals(0xFFFF, huge.u16(1))
        assertEquals(3 + 0xFFFF, huge.size)
    }

    @Test
    fun pingIsSingleByte() {
        assertArrayEquals(byteArrayOf(HidProtocol.CMD_PING.toByte()), HidProtocol.ping())
    }

    @Test
    fun parsesHandshakeAck() {
        val ack = HidProtocol.parseHandshakeAck(
            byteArrayOf(0x81.toByte(), 2, 0x38, 0x04, 0x60, 0x09),
        )

        assertEquals(HidProtocol.HandshakeAck(version = 2, width = 1080, height = 2400), ack)
        assertNull(HidProtocol.parseHandshakeAck(byteArrayOf(0x82.toByte(), 2, 0, 0, 0, 0)))
        assertNull(HidProtocol.parseHandshakeAck(byteArrayOf(0x81.toByte(), 2)))
    }

    @Test
    fun parsesStatusFlags() {
        val all = HidProtocol.parseStatus(byteArrayOf(0x83.toByte(), 0x0F))
        assertTrue(all!!.linkUp)
        assertTrue(all.encrypted)
        assertTrue(all.hidReady)
        assertTrue(all.appReady)

        val partial = HidProtocol.parseStatus(byteArrayOf(0x83.toByte(), 0x05))!!
        assertTrue(partial.linkUp)
        assertTrue(!partial.encrypted)
        assertTrue(partial.hidReady)
        assertTrue(!partial.appReady)
    }

    @Test
    fun ignoresNonStatusFrame() {
        assertNull(HidProtocol.parseStatus(byteArrayOf(0x82.toByte(), 0x0F)))
        assertNull(HidProtocol.parseStatus(byteArrayOf(0x83.toByte())))
    }

    @Test
    fun parsesErrorCode() {
        assertEquals(3, HidProtocol.parseError(byteArrayOf(0x84.toByte(), 3)))
        assertNull(HidProtocol.parseError(byteArrayOf(0x83.toByte(), 3)))
        assertNull(HidProtocol.parseError(byteArrayOf(0x84.toByte())))
    }

    @Test
    fun formatsKnownAndUnknownErrors() {
        assertEquals("固件未连上 HID 主机", HidProtocol.errorText(HidProtocol.ERR_HID_NOT_LINKED))
        assertEquals("HID 链路未就绪（未加密）", HidProtocol.errorText(HidProtocol.ERR_HID_NOT_READY))
        assertEquals("固件注入 HID 报表失败", HidProtocol.errorText(HidProtocol.ERR_INPUT_SET))
        assertEquals("未知错误 0x2a", HidProtocol.errorText(0x2A))
    }

    @Test
    fun kindReadsFirstByte() {
        assertEquals(0x87, HidProtocol.kind(byteArrayOf(0x87.toByte())))
        assertEquals(-1, HidProtocol.kind(ByteArray(0)))
    }
}
