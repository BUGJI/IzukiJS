package com.benton.izukijs.runtime

import com.benton.izukijs.model.UnlockResult

/** 运行前的解锁能力；生产实现为 `ScreenUnlockController`。 */
interface RunUnlocker {
    fun isEnabled(): Boolean

    suspend fun ensureUnlocked(): UnlockResult
}
