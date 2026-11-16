package com.benton.izukijs.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToastObserverTest {

    @Before
    fun setUp() {
        ToastObserver.clear()
        ToastObserver.setConnected(false)
    }

    @After
    fun tearDown() {
        ToastObserver.clear()
        ToastObserver.setConnected(false)
    }

    @Test
    fun acceptsOnlyNotificationStateChangedEvents() {
        assertTrue(record(eventType = ToastRules.TYPE_NOTIFICATION_STATE_CHANGED))
        assertFalse(record(eventType = 0x00000010))
    }

    @Test
    fun rejectsRealNotificationsAndOwnPackage() {
        assertFalse(record(parcelableIsNotification = true))
        assertFalse(record(packageName = "com.benton.izukijs", ownPackage = "com.benton.izukijs"))
        assertFalse(record(packageName = null))
        assertFalse(record(texts = listOf("  ", null)))
    }

    @Test
    fun normalizesTextsAndKeepsFirstAsText() {
        assertTrue(record(texts = listOf(" 保存成功 ", "", null, "已同步")))
        val item = ToastObserver.snapshot().single()
        assertEquals("保存成功", item.text)
        assertEquals(listOf("保存成功", "已同步"), item.texts)
        assertEquals("android.widget.Toast", item.className)
        assertEquals("com.example.app", item.packageName)
    }

    @Test
    fun keepsNewestFirstAndCapsAtFifty() {
        repeat(55) { index ->
            record(texts = listOf("toast-$index"), time = index.toLong())
        }
        val snapshot = ToastObserver.snapshot()
        assertEquals(50, snapshot.size)
        assertEquals("toast-54", snapshot.first().text)
        assertEquals("toast-5", snapshot.last().text)
    }

    @Test
    fun matchesByPackageContainsAndSince() {
        val item = ToastItem(
            packageName = "com.example.app",
            text = "保存成功",
            texts = listOf("保存成功", "已同步"),
            className = "android.widget.Toast",
            time = 1_000L,
        )
        assertTrue(item.matches(null, null, 0L))
        assertTrue(item.matches("com.example.app", "成功", 0L))
        assertTrue(item.matches("com.example.app", "已同步", 1_000L))
        assertFalse(item.matches("com.other", null, 0L))
        assertFalse(item.matches(null, "失败", 0L))
        assertFalse(item.matches(null, null, 1_001L))
    }

    @Test
    fun clearRemovesHistoryButKeepsConnectionState() {
        ToastObserver.setConnected(true)
        record()
        assertEquals(1, ToastObserver.count())
        ToastObserver.clear()
        assertEquals(0, ToastObserver.count())
        assertTrue(ToastObserver.connected)
    }

    private fun record(
        eventType: Int = ToastRules.TYPE_NOTIFICATION_STATE_CHANGED,
        parcelableIsNotification: Boolean = false,
        packageName: String? = "com.example.app",
        ownPackage: String? = "com.benton.izukijs",
        texts: List<String?> = listOf("保存成功"),
        className: String? = "android.widget.Toast",
        time: Long = System.currentTimeMillis(),
    ): Boolean = ToastObserver.recordFrom(
        eventType = eventType,
        parcelableIsNotification = parcelableIsNotification,
        packageName = packageName,
        ownPackage = ownPackage,
        texts = texts,
        className = className,
        time = time,
    )
}
