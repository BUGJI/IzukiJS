package com.benton.izukijs.runtime

/**
 * 单槽待执行队列。后到的请求覆盖先前的请求，并返回被覆盖项，
 * 由调用方负责通知其所有者「本次不会启动」。
 *
 * 不做内部同步：调用方必须在同一把决策锁内完成「判断运行位 + 入队 / 出队」，
 * 否则会和提升（promotion）产生竞争。
 */
internal class RunQueue<T> {

    private var pending: T? = null

    /** 覆盖已有待执行项；返回被顶替的项。 */
    fun offer(item: T): T? {
        val previous = pending
        pending = item
        return previous
    }

    /** 取出待执行项；没有则返回 null。 */
    fun take(): T? {
        val item = pending
        pending = null
        return item
    }

    fun peek(): T? = pending

    fun clear(): T? = take()
}
