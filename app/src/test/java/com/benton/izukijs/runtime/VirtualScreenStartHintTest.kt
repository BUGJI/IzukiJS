package com.benton.izukijs.runtime

import com.benton.izukijs.virtualscreen.VirtualScreenResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualScreenStartHintTest {

    private val base = VirtualScreenResolution(1280, 720, 240)

    @Test
    fun startCallPatternEscapesClosingBrace() {
        // Android 的 ICU 正则把裸 `}` 判为语法错误，JVM 的 java.util.regex 不会，
        // 单测里编译通过也不代表设备上不崩，所以直接校验模式串的结尾转义。
        assertTrue(
            "收尾的 `}` 必须写成 `\\}`，否则 Android 上静态初始化会抛 PatternSyntaxException",
            VirtualScreenStartHint.START_CALL_PATTERN.endsWith("\\}"),
        )
    }

    @Test
    fun parsesLiteralStartOptions() {
        val source = """
            // @need virtualscreen
            virtualScreen.start({ width: 720, height: 1280, dpi: 320 });
        """.trimIndent()
        assertEquals(
            VirtualScreenResolution(720, 1280, 320),
            VirtualScreenStartHint.parse(source, base),
        )
    }

    @Test
    fun parsesShortKeysAndFallsBackForMissingDpi() {
        val source = """virtualScreen.start({ w: 900, h: 1600 });"""
        assertEquals(
            VirtualScreenResolution(900, 1600, 240),
            VirtualScreenStartHint.parse(source, base),
        )
    }

    @Test
    fun ignoresCommentedOutCalls() {
        val source = """
            // virtualScreen.start({ width: 720, height: 1280 });
            /* virtualScreen.start({ width: 600, height: 900 }); */
        """.trimIndent()
        assertNull(VirtualScreenStartHint.parse(source, base))
    }

    @Test
    fun ignoresCallsInsideStrings() {
        val source = """log("virtualScreen.start({ width: 720, height: 1280 })");"""
        assertNull(VirtualScreenStartHint.parse(source, base))
    }

    @Test
    fun dynamicOptionsAreIgnored() {
        val source = """virtualScreen.start({ width: env.get("W"), height: 1280 });"""
        assertNull(VirtualScreenStartHint.parse(source, base))
    }

    @Test
    fun requiresBothDimensions() {
        val source = """virtualScreen.start({ width: 720 });"""
        assertNull(VirtualScreenStartHint.parse(source, base))
    }
}
