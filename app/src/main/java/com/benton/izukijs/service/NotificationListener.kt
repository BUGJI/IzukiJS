package com.benton.izukijs.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 一条通知的快照。字段取自 [Notification.extras] 的常用键。 */
data class NotificationItem(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val subText: String,
    val postTime: Long,
    val ongoing: Boolean,
)

/**
 * 通知监听服务。只读：抓取通知内容供脚本 / MCP / AI 使用，并在内存里保留最近若干条。
 *
 * 抓取内容通过 [Store] 以 [StateFlow] 暴露；脚本侧经由 `notify` 命名空间的
 * [com.benton.izukijs.runtime.api.NotificationApi] 访问。
 */
class NotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        Store.setConnected(true)
        // 连接时用当前活动通知预热缓冲，避免刚开启漏掉已在通知栏的项。
        runCatching {
            activeNotifications.orEmpty().forEach { Store.onPosted(it, prepend = false) }
        }
    }

    override fun onListenerDisconnected() {
        Store.setConnected(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Store.onPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        Store.onRemoved(sbn.key)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        Store.setConnected(false)
        super.onDestroy()
    }

    /** 供 [com.benton.izukijs.runtime.api.NotificationApi] 主动清除通知。 */
    fun cancel(key: String): Boolean = runCatching {
        cancelNotification(key)
        true
    }.getOrDefault(false)

    companion object {
        @Volatile
        private var instance: NotificationListener? = null

        internal fun cancelByKey(key: String): Boolean = instance?.cancel(key) ?: false
    }

    /** 最近通知的内存缓冲，容量 [MAX_ITEMS]。 */
    object Store {

        private const val MAX_ITEMS = 50

        private val _items = MutableStateFlow<List<NotificationItem>>(emptyList())
        val items: StateFlow<List<NotificationItem>> = _items.asStateFlow()

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        fun setConnected(value: Boolean) {
            _connected.value = value
        }

        fun snapshot(): List<NotificationItem> = _items.value

        @Synchronized
        fun onPosted(sbn: StatusBarNotification, prepend: Boolean = true) {
            val item = sbn.toItem() ?: return
            val current = _items.value
            val withoutOld = current.filterNot { it.key == item.key }
            _items.value = if (prepend) {
                (listOf(item) + withoutOld).take(MAX_ITEMS)
            } else {
                (withoutOld + item).takeLast(MAX_ITEMS)
            }
        }

        @Synchronized
        fun onRemoved(key: String) {
            _items.value = _items.value.filterNot { it.key == key }
        }

        private fun StatusBarNotification.toItem(): NotificationItem? = runCatching {
            val extras = notification?.extras ?: return@runCatching null
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
            NotificationItem(
                key = key,
                packageName = packageName,
                title = title,
                text = text,
                subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(),
                postTime = postTime,
                ongoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            )
        }.getOrNull()
    }
}
