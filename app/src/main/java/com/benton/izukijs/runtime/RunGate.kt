package com.benton.izukijs.runtime

/**
 * 脚本运行互斥与停止请求状态机。
 *
 * 只处理与 Android / QuickJS 无关的「判断 + 更新」：谁在运行、引擎是否已创建、
 * 停止请求是否已到达。所有方法都在同一把锁内完成判断与更新，因此可以直接做并发单测，
 * 不必拉起完整运行时。
 *
 * 关键约束：
 * - 同一时刻只允许一个 run；
 * - 引擎创建前的停止请求必须挂起，绑定引擎后立即生效；
 * - 旧 run 的收尾不得清空新 run 的引擎或运行位（ABA）。
 */
internal class RunGate<E : Any> {

    private val lock = Any()
    private var activeRunId: String? = null
    private var engine: E? = null
    private var stopRequested = false

    fun isRunning(): Boolean = synchronized(lock) { activeRunId != null }

    fun activeRunId(): String? = synchronized(lock) { activeRunId }

    /** 尝试占用运行位；已有运行返回 false。成功后清空上一次的停止请求。 */
    fun tryStart(runId: String): Boolean = synchronized(lock) {
        if (activeRunId != null) return false
        activeRunId = runId
        engine = null
        stopRequested = false
        true
    }

    /**
     * 绑定本次 run 的引擎，返回「绑定前是否已收到停止请求」。
     *
     * 若 [runId] 已不是活动 run（被强制重置），不绑定并返回 true，让调用方立即退出，
     * 避免旧引擎继续执行。
     */
    fun attachEngine(runId: String, engine: E): Boolean = synchronized(lock) {
        if (activeRunId != runId) return true
        this.engine = engine
        stopRequested
    }

    /**
     * 解除引擎绑定并返回需要关闭的引擎。
     *
     * 只有 [runId] 仍是活动 run 时才清空共享字段；旧 run 的收尾不会碰新 run 的引擎。
     */
    fun detachEngine(runId: String): E? = synchronized(lock) {
        if (activeRunId != runId) return null
        val current = engine
        engine = null
        current
    }

    /** 解析停止动作：已有引擎立即停止；仍在启动则挂起请求。 */
    fun resolveStop(): StopAction<E> = synchronized(lock) {
        val current = engine
        if (current != null) return StopAction.StopEngine(current)
        if (activeRunId != null) {
            stopRequested = true
            return StopAction.WaitStart
        }
        StopAction.None
    }

    /** 当前 run 是否收到过停止请求。 */
    fun isStopRequested(): Boolean = synchronized(lock) { stopRequested }

    /** 仅当 [runId] 仍是活动 run 时重置；返回是否真正重置。 */
    fun reset(runId: String): Boolean = synchronized(lock) {
        if (activeRunId != runId) return false
        activeRunId = null
        engine = null
        stopRequested = false
        true
    }

    internal sealed interface StopAction<out E> {
        data class StopEngine<out E>(val engine: E) : StopAction<E>

        data object WaitStart : StopAction<Nothing>

        data object None : StopAction<Nothing>
    }
}
