package com.benton.izukijs.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionSyncTest {

    @Test
    fun intervalIsClampedToMinimum() {
        assertEquals(
            SubscriptionSync.MIN_INTERVAL_MINUTES,
            SubscriptionSync.normalizeInterval(1L),
        )
        assertEquals(
            SubscriptionSync.DEFAULT_INTERVAL_MINUTES,
            SubscriptionSync.normalizeInterval(null),
        )
        assertEquals(60L, SubscriptionSync.normalizeInterval(60L))
    }

    @Test
    fun zeroIntervalMeansDisabled() {
        assertEquals(0L, SubscriptionSync.normalizeInterval(0L))
        assertTrue(SubscriptionSync.isDisabled(0L))
        assertFalse(SubscriptionSync.isDisabled(SubscriptionSync.MIN_INTERVAL_MINUTES))
    }

    @Test
    fun hashIsStableAndDistinct() {
        assertEquals(SubscriptionSync.sha256("toast(1)"), SubscriptionSync.sha256("toast(1)"))
        assertNotEquals(SubscriptionSync.sha256("toast(1)"), SubscriptionSync.sha256("toast(2)"))
    }

    @Test
    fun identicalContentIsNotOverwritten() {
        assertFalse(SubscriptionSync.shouldOverwrite("same", "same", ""))
    }

    @Test
    fun unchangedRemoteIsNotOverwritten() {
        val remote = "remote"
        val hash = SubscriptionSync.sha256(remote)
        assertFalse(SubscriptionSync.shouldOverwrite("local edit", remote, hash))
    }

    @Test
    fun changedRemoteIsOverwritten() {
        val previous = SubscriptionSync.sha256("old remote")
        assertTrue(SubscriptionSync.shouldOverwrite("local edit", "new remote", previous))
        assertTrue(SubscriptionSync.shouldOverwrite("local edit", "new remote", ""))
    }
}
