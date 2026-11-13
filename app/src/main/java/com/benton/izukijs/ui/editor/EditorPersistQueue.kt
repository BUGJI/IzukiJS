package com.benton.izukijs.ui.editor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一次保存请求的最终结果；被更新版本取代时返回最终版本的成败。 */
sealed interface PersistOutcome {
    data class Saved(val content: String) : PersistOutcome

    data class Conflict(val actualSha256: String) : PersistOutcome

    data class Failed(val message: String? = null) : PersistOutcome
}

/**
 * 按脚本串行化写盘，并保证“被更新内容取代”的调用等待最终写入结果。
 *
 * - 版本号在调用时分配，写锁保证真正写盘的顺序与最终版本一致；
 * - 被取代的调用不会直接返回成功，而是等待最终版本的结果，避免返回/运行早于落盘；
 * - 每个调用都会完成自己的 deferred，形成向最终版本的等待链，避免中间版本永久挂起。
 */
class EditorPersistQueue {

    private val lock = Any()
    private val writeMutex = Mutex()
    private val versions = mutableMapOf<String, Long>()
    private val latest = mutableMapOf<String, CompletableDeferred<PersistOutcome>>()

    suspend fun run(key: String, write: suspend () -> PersistOutcome): PersistOutcome {
        val (version, own) = synchronized(lock) {
            val next = (versions[key] ?: 0L) + 1L
            versions[key] = next
            val deferred = CompletableDeferred<PersistOutcome>()
            latest[key] = deferred
            next to deferred
        }

        while (true) {
            val isLatest = synchronized(lock) { versions[key] == version }
            if (!isLatest) {
                val pending = synchronized(lock) { latest[key] ?: own }
                val outcome = pending.await()
                own.complete(outcome)
                return outcome
            }

            var outcome: PersistOutcome? = null
            writeMutex.withLock {
                val stillLatest = synchronized(lock) { versions[key] == version }
                if (stillLatest) {
                    outcome = try {
                        write()
                    } catch (t: Throwable) {
                        PersistOutcome.Failed(t.message)
                    }
                    own.complete(outcome)
                }
            }
            outcome?.let { return it }

            // 等锁期间被更新版本取代：等待新的最终结果，并把结果继续传给依赖自己的调用。
            val pending = synchronized(lock) { latest[key] ?: own }
            if (pending !== own) {
                val waited = pending.await()
                own.complete(waited)
                return waited
            }
        }
    }
}
