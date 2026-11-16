package com.benton.izukijs.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
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
        // 连接时用当前活动通知重建缓冲，避免系统重绑后只拿到增量而丢掉已在通知栏的项。
        refreshActive()
    }

    /**
     * 用系统当前活动通知重建内存缓冲，返回实际读取到的条数。
     *
     * 服务未连接或系统拒绝读取时返回 0，调用方应结合 [Store.connected] 判断是
     * 「真的没有通知」还是「监听未绑定」。
     */
    fun refreshActive(): Int {
        if (instance !== this) return 0
        val active = runCatching { activeNotifications }.getOrNull().orEmpty()
        Store.replaceAll(active)
        return active.size
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

    /** 点击通知的 contentIntent；通知已消失或没有点击意图时返回 false。 */
    fun click(key: String): Boolean = runCatching {
        val sbn = activeNotifications?.firstOrNull { it.key == key } ?: return@runCatching false
        val intent = sbn.notification?.contentIntent ?: return@runCatching false
        intent.send()
        true
    }.getOrDefault(false)

    /**
     * 删除通知：优先触发应用自己的 deleteIntent（与用户划掉通知一致），
     * 没有时退回系统取消。
     */
    fun delete(key: String): Boolean = runCatching {
        val sbn = activeNotifications?.firstOrNull { it.key == key }
        val intent = sbn?.notification?.deleteIntent
        if (intent != null) {
            intent.send()
            true
        } else {
            cancel(key)
        }
    }.getOrDefault(false)

    companion object {
        @Volatile
        private var instance: NotificationListener? = null

        internal fun cancelByKey(key: String): Boolean = instance?.cancel(key) ?: false

        internal fun clickByKey(key: String): Boolean = instance?.click(key) ?: false

        internal fun deleteByKey(key: String): Boolean = instance?.delete(key) ?: false

        /** 当前服务实例是否已与系统建立监听连接。 */
        fun isConnected(): Boolean = Store.connected.value

        /** 用系统活动通知刷新缓冲；未连接时返回 0。 */
        fun refreshActive(): Int = instance?.refreshActive() ?: 0

        /**
         * 请求系统重新绑定监听服务。
         *
         * Android 在部分 ROM 上会在进程重启 / 后台限制后保留授权状态但不恢复连接，
         * 此时仅检查权限会误判为「已开启」，需要主动触发一次重绑。
         */
        fun requestRebind(context: Context) {
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(context.applicationContext, NotificationListener::class.java),
                )
            }
        }
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

        /** 用系统当前活动通知整体替换缓冲，保持与通知栏一致。 */
        @Synchronized
        fun replaceAll(sbns: Array<out StatusBarNotification>) {
            _items.value = sbns.mapNotNull { it.toItem() }
                .sortedByDescending { it.postTime }
                .take(MAX_ITEMS)
        }

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
