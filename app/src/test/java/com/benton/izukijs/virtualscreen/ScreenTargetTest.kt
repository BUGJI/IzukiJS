package com.benton.izukijs.virtualscreen

import com.benton.izukijs.model.RunMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenTargetTest {

    @Test
    fun parsesCaseInsensitiveNames() {
        assertEquals(ScreenTarget.AUTO, ScreenTarget.from("auto"))
        assertEquals(ScreenTarget.VIRTUAL, ScreenTarget.from(" VIRTUAL "))
        assertEquals(ScreenTarget.MAIN, ScreenTarget.from("Main"))
    }

    @Test
    fun rejectsUnknownValues() {
        assertNull(ScreenTarget.from(null))
        assertNull(ScreenTarget.from(""))
        assertNull(ScreenTarget.from("display"))
    }

    @Test
    fun displayNameIsLowercase() {
        assertEquals("auto", ScreenTarget.AUTO.displayName())
        assertEquals("virtual", ScreenTarget.VIRTUAL.displayName())
        assertEquals("main", ScreenTarget.MAIN.displayName())
    }

    @Test
    fun defaultTargetFollowsRunMode() {
        assertEquals(ScreenTarget.AUTO, ScreenTarget.defaultFor(RunMode.BACKGROUND))
        assertEquals(ScreenTarget.MAIN, ScreenTarget.defaultFor(RunMode.FOREGROUND))
    }
}
