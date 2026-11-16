package com.benton.izukijs.service

/**
 * 无障碍 `TYPE_NOTIFICATION_STATE_CHANGED` 事件里解析出的 toast 快照。
 *
 * 系统通知同样使用该事件类型，因此只有 `parcelableData` 不是
 * [android.app.Notification] 的事件才视为 toast；本应用自己弹出的 toast 也会被过滤，
 * 避免脚本调用 `toast()` 后把自己的提示当成外部消息。
 */
data class ToastItem(
    val packageName: String,
    val text: String,
    val texts: List<String>,
    val className: String,
    val time: Long,
) {
    /** 等待用的纯逻辑匹配：包名精确、文本包含、时间不早于 [since]。 */
    fun matches(packageName: String?, contains: String?, since: Long): Boolean {
        if (time < since) return false
        if (packageName != null && this.packageName != packageName) return false
        if (contains != null && texts.none { it.contains(contains) }) return false
        return true
    }
}

/**
 * toast 事件判定。抽成不依赖 `android.*` 的纯逻辑，便于 JVM 单测。
 */
internal object ToastRules {

    /**
     * [android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED]
     * 的常量值；这里不引用 Android 类，保持纯逻辑可测。
     */
    const val TYPE_NOTIFICATION_STATE_CHANGED = 0x00000040

    fun shouldRecord(
        eventType: Int,
        parcelableIsNotification: Boolean,
        packageName: String?,
        ownPackage: String?,
        texts: List<String?>,
    ): Boolean {
        if (eventType != TYPE_NOTIFICATION_STATE_CHANGED) return false
        if (parcelableIsNotification) return false
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        if (ownPackage != null && pkg == ownPackage) return false
        return texts.any { !it.isNullOrBlank() }
    }
}

/**
 * toast 的内存缓冲。
 *
 * 与 [NotificationListener.Store] 保持一致：只保留最近 [MAX_ITEMS] 条，新的在前。
 * 写入发生在无障碍服务线程，读取发生在脚本 / MCP 线程，因此全部访问加锁。
 */
object ToastObserver {

    private const val MAX_ITEMS = 50

    private val items = ArrayDeque<ToastItem>()

    @Volatile
    var connected: Boolean = false
        private set

    fun setConnected(value: Boolean) {
        connected = value
    }

    /**
     * 从无障碍事件字段记录一条 toast，返回是否真正入队。
     *
     * 时间参数可注入，便于测试「调用之后才出现」的等待语义。
     */
    @Synchronized
    fun recordFrom(
        eventType: Int,
        parcelableIsNotification: Boolean,
        packageName: String?,
        ownPackage: String?,
        texts: List<String?>,
        className: String? = null,
        time: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!ToastRules.shouldRecord(eventType, parcelableIsNotification, packageName, ownPackage, texts)) {
            return false
        }
        val normalized = texts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        if (normalized.isEmpty()) return false
        val item = ToastItem(
            packageName = packageName?.trim().orEmpty(),
            text = normalized.first(),
            texts = normalized,
            className = className?.trim().orEmpty(),
            time = time,
        )
        items.addFirst(item)
        while (items.size > MAX_ITEMS) {
            items.removeLast()
        }
        return true
    }

    /** 当前缓冲，新的在前。 */
    @Synchronized
    fun snapshot(): List<ToastItem> = items.toList()

    @Synchronized
    fun count(): Int = items.size

    @Synchronized
    fun clear() {
        items.clear()
    }
}
