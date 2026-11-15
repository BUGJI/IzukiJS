package com.benton.izukijs.ui.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualScreenAppsTest {

    private val apps = listOf(
        VirtualScreenApp("com.example.chat", "Chat", isSystem = false),
        VirtualScreenApp("com.example.settings", "Settings", isSystem = true),
        VirtualScreenApp("com.benton.izukijs", "IzukiJS"),
    )

    @Test
    fun blankQueryReturnsEveryApp() {
        assertEquals(apps, filterVirtualScreenApps(apps, ""))
        assertEquals(apps, filterVirtualScreenApps(apps, "   "))
    }

    @Test
    fun matchesLabelIgnoringCase() {
        assertEquals(
            listOf(apps[0]),
            filterVirtualScreenApps(apps, "chat"),
        )
    }

    @Test
    fun matchesPackageName() {
        assertEquals(
            listOf(apps[1]),
            filterVirtualScreenApps(apps, "settings"),
        )
    }

    @Test
    fun trimsQueryAndReturnsEmptyWhenNothingMatches() {
        assertTrue(filterVirtualScreenApps(apps, "  missing  ").isEmpty())
    }

    @Test
    fun limitCapsResults() {
        assertEquals(2, filterVirtualScreenApps(apps, "", limit = 2).size)
        assertEquals(0, filterVirtualScreenApps(apps, "", limit = 0).size)
    }
}
