package com.benton.izukijs.privileged.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayIdentityPolicyTest {

    @Test
    fun rootNeedsShellDrop() {
        assertTrue(DisplayIdentityPolicy.requiresShellDrop(0))
        assertEquals(2000, DisplayIdentityPolicy.displayUid(0))
    }

    @Test
    fun shellAndAppUidsKeepTheirIdentity() {
        assertFalse(DisplayIdentityPolicy.requiresShellDrop(2000))
        assertEquals(2000, DisplayIdentityPolicy.displayUid(2000))

        assertFalse(DisplayIdentityPolicy.requiresShellDrop(10314))
        assertEquals(10314, DisplayIdentityPolicy.displayUid(10314))
    }

    @Test
    fun systemUidKeepsSystemIdentity() {
        assertFalse(DisplayIdentityPolicy.requiresShellDrop(1000))
        assertEquals(1000, DisplayIdentityPolicy.displayUid(1000))
    }
}
