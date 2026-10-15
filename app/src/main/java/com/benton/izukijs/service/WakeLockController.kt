package com.benton.izukijs.service

import android.content.Context
import android.os.PowerManager

/**
 * 脚本运行期间的 CPU 唤醒锁。引用计数，支持嵌套持有；带安全超时，避免异常路径下永久持有。
 *
 * 只负责 PARTIAL_WAKE_LOCK（保证 CPU 不休眠）；亮屏由调用方在需要时另行处理。
 */
class WakeLockController(context: Context) {

    private val power: PowerManager? =
        context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var lock: PowerManager.WakeLock? = null

    private var refCount = 0

    @Synchronized
    fun acquire() {
        if (refCount == 0) {
            lock = runCatching {
                power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            }.getOrNull()
            runCatching { lock?.acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
        refCount++
    }

    @Synchronized
    fun release() {
        if (refCount == 0) return
        refCount--
        if (refCount == 0) {
            runCatching { if (lock?.isHeld == true) lock?.release() }
            lock = null
        }
    }

    private companion object {
        const val WAKE_LOCK_TAG = "izuki:script"
        const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L
    }
}
