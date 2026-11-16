package com.benton.izukijs.service

/**
 * 一条经过裁剪的无障碍事件。
 *
 * 不保存 `AccessibilityEvent` / `AccessibilityNodeInfo`，只复制脚本需要的字段，
 * 避免框架回收对象后访问崩溃。
 */
data class A11yEvent(
    val seq: Long,
    val type: String,
    val packageName: String?,
    val className: String?,
    val text: String,
    val contentDescription: String?,
    val windowId: Int,
    val time: Long,
    val keyCode: Int? = null,
)

/**
 * 进程级无障碍事件缓冲。
 *
 * 写入发生在无障碍服务线程，读取发生在脚本 / MCP 线程；所有访问加锁。
 * `waitChange` 用对象监视器唤醒选择器轮询，避免静态页面空转。
 */
object AccessibilityEventStore {

    private const val MAX_EVENTS = 200

    private val events = ArrayDeque<A11yEvent>()
    private val lastByType = HashMap<String, Long>()
    private var seqCounter = 0L

    /**
     * 记录一条事件。窗口内容变化等高频事件由系统 `notificationTimeout` 节流，
     * 这里只做数量上限保护。
     */
    @Synchronized
    fun record(
        type: String,
        packageName: String? = null,
        className: String? = null,
        text: String = "",
        contentDescription: String? = null,
        windowId: Int = -1,
        time: Long = System.currentTimeMillis(),
        keyCode: Int? = null,
    ): A11yEvent {
        val event = A11yEvent(
            seq = ++seqCounter,
            type = type,
            packageName = packageName,
            className = className,
            text = text,
            contentDescription = contentDescription,
            windowId = windowId,
            time = time,
            keyCode = keyCode,
        )
        events.addFirst(event)
        while (events.size > MAX_EVENTS) {
            events.removeLast()
        }
        lastByType[type] = time
        (this as java.lang.Object).notifyAll()
        return event
    }

    /** 当前序号；脚本可记录起点，只读取之后的事件。 */
    @Synchronized
    fun sequence(): Long = seqCounter

    /** 最近事件，新的在前。 */
    @Synchronized
    fun snapshot(
        sinceSeq: Long = 0L,
        types: Set<String>? = null,
        limit: Int = MAX_EVENTS,
    ): List<A11yEvent> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<A11yEvent>(minOf(limit, events.size))
        for (event in events) {
            if (event.seq <= sinceSeq) continue
            if (types != null && event.type !in types) continue
            out.add(event)
            if (out.size >= limit) break
        }
        return out
    }

    /** 指定类型最近一次事件的时间；没有记录时返回 0。 */
    @Synchronized
    fun lastEventAt(types: Set<String>? = null): Long {
        if (types == null) return lastByType.values.maxOrNull() ?: 0L
        var latest = 0L
        for (type in types) {
            val time = lastByType[type] ?: continue
            if (time > latest) latest = time
        }
        return latest
    }

    /**
     * 等待序号超过 [afterSeq]，或超时返回 false。
     *
     * 用 `wait/notify` 而不是固定 sleep：选择器轮询在界面变化时立即醒来，
     * 静态页面则睡满 [timeoutMs]。
     */
    @Synchronized
    fun awaitChange(afterSeq: Long, timeoutMs: Long): Boolean {
        if (timeoutMs <= 0L) return seqCounter > afterSeq
        val deadline = System.currentTimeMillis() + timeoutMs
        while (seqCounter <= afterSeq) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) return false
            try {
                (this as java.lang.Object).wait(remaining)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    /**
     * 清空缓冲。
     *
     * 序号必须保持单调递增：脚本 / 选择器可能已经记下 `afterSeq`，序号一旦回退，
     * 这些等待方会认为「还没有新事件」而一直等到超时。因此这里只清内容，不复位 [seqCounter]。
     */
    @Synchronized
    fun clear() {
        events.clear()
        lastByType.clear()
        (this as java.lang.Object).notifyAll()
    }
}
