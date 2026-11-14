package com.benton.izukijs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunFailureTextTest {

    @Test
    fun blankMessageFallsBackToClassName() {
        assertEquals("RuntimeException", RuntimeException().describeForLog())
        assertEquals("RuntimeException", RuntimeException("   ").describeForLog())
    }

    @Test
    fun keepsOwnMessageWhenNoCause() {
        assertEquals("boom", RuntimeException("boom").describeForLog())
    }

    @Test
    fun appendsRootCause() {
        val error = IllegalStateException(
            "outer",
            IllegalArgumentException("inner"),
        )

        assertEquals(
            "outer ← IllegalArgumentException: inner",
            error.describeForLog(),
        )
    }

    @Test
    fun wrapperWithoutMessageStillShowsRootCause() {
        val error = Exception(null, IllegalArgumentException("inner"))

        assertEquals(
            "Exception ← IllegalArgumentException: inner",
            error.describeForLog(),
        )
    }

    @Test
    fun rootWithoutMessageShowsClassNameOnly() {
        val error = RuntimeException("outer", IllegalStateException())

        assertEquals(
            "outer ← IllegalStateException",
            error.describeForLog(),
        )
    }

    @Test
    fun cyclicCauseChainTerminates() {
        val first = RuntimeException()
        val second = RuntimeException(first)
        first.initCause(second)

        val text = first.describeForLog()

        assertTrue(text.contains("RuntimeException"))
    }
}
