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
    fun zeroIntervalDisablesAutomaticScheduling() {
        assertEquals(0L, SubscriptionSync.normalizeInterval(0L))
        assertTrue(SubscriptionSync.isDisabled(0L))
        assertFalse(SubscriptionSync.isDisabled(SubscriptionSync.MIN_INTERVAL_MINUTES))
    }

    @Test
    fun manualOnlySubscriptionCanStillSyncManually() {
        val subscription = ScriptSubscription("id", "https://example.com/a.js", 0, enabled = false)
        assertTrue(SubscriptionSync.canSync(subscription, automatic = false, now = 100))
        assertFalse(SubscriptionSync.canSync(subscription, automatic = true, now = 100))
    }

    @Test
    fun automaticWorkMustStillBeEnabledAndDueAfterQueueing() {
        val due = ScriptSubscription("id", "https://example.com/a.js", 15, nextSyncAtMillis = 100)
        assertTrue(SubscriptionSync.canSync(due, automatic = true, now = 100))
        assertFalse(SubscriptionSync.canSync(due.copy(enabled = false), automatic = true, now = 100))
        assertFalse(SubscriptionSync.canSync(due.copy(nextSyncAtMillis = 200), automatic = true, now = 100))
    }

    @Test
    fun updatedSettingsPreserveLatestSyncMetadataForSameUrl() {
        val latest = ScriptSubscription("id", "https://example.com/a.js", 60,
            contentHash = "latest-hash", etag = "latest-etag", lastSyncAtMillis = 42)
        val stale = latest.copy(enabled = false, intervalMinutes = 0, contentHash = "stale", etag = "stale")
        val result = SubscriptionSync.applySettings(stale, latest)
        assertFalse(result.enabled)
        assertEquals(0L, result.intervalMinutes)
        assertEquals("latest-hash", result.contentHash)
        assertEquals("latest-etag", result.etag)
    }

    @Test
    fun changedUrlClearsOldConditionalHeadersAndBaseline() {
        val previous = ScriptSubscription("id", "https://example.com/a.js", 60,
            contentHash = "old-hash", etag = "old-etag", lastModified = "old-date", lastError = "old")
        val result = SubscriptionSync.applySettings(previous.copy(url = "https://example.com/b.js"), previous)
        assertEquals("", result.contentHash)
        assertEquals("", result.etag)
        assertEquals("", result.lastModified)
        assertEquals("", result.lastError)
    }

    @Test
    fun newSubscriptionDoesNotTrustIncomingBaseline() {
        val incoming = ScriptSubscription("id", "https://example.com/a.js", 0,
            contentHash = "unverified", etag = "unverified")
        assertEquals("", SubscriptionSync.applySettings(incoming, null).contentHash)
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
