package com.benton.izukijs.controller.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellGlobalActionsTest {

    @Test
    fun basicNavigationMapsToKeyEvents() {
        assertEquals("input keyevent 4", ShellGlobalActions.commandFor(ShellGlobalActions.BACK, ""))
        assertEquals("input keyevent 3", ShellGlobalActions.commandFor(ShellGlobalActions.HOME, ""))
        assertEquals(
            "input keyevent 187",
            ShellGlobalActions.commandFor(ShellGlobalActions.RECENTS, ""),
        )
    }

    @Test
    fun displayOptionIsPlacedBeforeKeyEvent() {
        assertEquals(
            "input -d 2 keyevent 4",
            ShellGlobalActions.commandFor(ShellGlobalActions.BACK, "-d 2 "),
        )
    }

    @Test
    fun systemPanelsUseStatusBarCommands() {
        assertEquals(
            "cmd statusbar expand-notifications",
            ShellGlobalActions.commandFor(ShellGlobalActions.NOTIFICATIONS, ""),
        )
        assertEquals(
            "cmd statusbar expand-settings",
            ShellGlobalActions.commandFor(ShellGlobalActions.QUICK_SETTINGS, ""),
        )
        assertEquals(
            "cmd statusbar collapse",
            ShellGlobalActions.commandFor(ShellGlobalActions.DISMISS_NOTIFICATION_SHADE, ""),
        )
    }

    @Test
    fun powerAndLockDoNotAliasRawAccessibilityCodes() {
        assertEquals(
            "input keyevent --longpress 26",
            ShellGlobalActions.commandFor(ShellGlobalActions.POWER_DIALOG, ""),
        )
        assertEquals(
            "input keyevent 223",
            ShellGlobalActions.commandFor(ShellGlobalActions.LOCK_SCREEN, ""),
        )
    }

    @Test
    fun unsupportedActionsFailClosed() {
        assertNull(ShellGlobalActions.commandFor(ShellGlobalActions.SPLIT_SCREEN, ""))
        assertNull(ShellGlobalActions.commandFor(9, ""))
        assertNull(ShellGlobalActions.commandFor(10, ""))
        assertNull(ShellGlobalActions.commandFor(99, ""))
        assertTrue(ShellGlobalActions.commandFor(ShellGlobalActions.BACK, "")!!.startsWith("input "))
    }
}
