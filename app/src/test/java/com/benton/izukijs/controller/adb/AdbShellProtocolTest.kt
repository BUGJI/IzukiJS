package com.benton.izukijs.controller.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbShellProtocolTest {

    @Test
    fun wrap_quotesSingleQuotesForPosixShell() {
        val wrapped = AdbShellProtocol.wrap("echo 'a b'", "MARK")
        assertTrue(wrapped.startsWith("sh -c "))
        assertTrue(wrapped.contains("'echo '\\''a b'\\'''"))
        assertTrue(wrapped.endsWith("echo 'MARK:'\$izuki_ec"))
    }

    @Test
    fun parse_extractsStdoutAndExitCode() {
        val result = AdbShellProtocol.parse("hello\nMARK:0\n", "MARK")
        assertEquals("hello", result.stdout)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun parse_handlesMissingTrailingNewline() {
        val result = AdbShellProtocol.parse("helloMARK:7", "MARK")
        assertEquals("hello", result.stdout)
        assertEquals(7, result.exitCode)
    }

    @Test
    fun parse_missingMarkerReturnsMinusOne() {
        val result = AdbShellProtocol.parse("only output", "MARK")
        assertEquals("only output", result.stdout)
        assertEquals(-1, result.exitCode)
    }

    @Test
    fun pngMagicDetection() {
        assertTrue(AdbShellProtocol.isPng(AdbShellProtocol.PNG_MAGIC.copyOf()))
        assertFalse(AdbShellProtocol.isPng(byteArrayOf(1, 2, 3)))
    }
}
