package com.benton.izukijs.privileged.internal

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import com.benton.izukijs.model.UnlockPattern
import com.benton.izukijs.model.UnlockResult

/**
 * 特权进程内的亮屏/锁屏/解锁。所有 hidden API 都只在这个进程里使用。
 */
internal class WakeUnlockController(private val context: Context?) {

    private val power = HiddenPowerManager.create()
    private val window = HiddenWindowManager.create()
    private val input = PrivilegedInput(context)

    fun testUnlock(credential: String): UnlockResult = keepCpuAwake(TEST_CPU_LOCK_MS) {
        val lock = lockAndSleep()
        if (lock != UnlockResult.OK) return@keepCpuAwake lock
        Thread.sleep(LOCK_SETTLE_MS)
        unlock(credential)
    }

    fun testUnlockGesture(gestureJson: String): UnlockResult = keepCpuAwake(TEST_CPU_LOCK_MS) {
        val lock = lockAndSleep()
        if (lock != UnlockResult.OK) return@keepCpuAwake lock
        Thread.sleep(LOCK_SETTLE_MS)
        unlockWithGesture(gestureJson)
    }

    fun lockAndSleep(): UnlockResult {
        val window = window ?: return UnlockResult.UNSUPPORTED
        if (!window.lockNow()) {
            PrivilegedLog.w("lockNow unavailable")
            return UnlockResult.UNSUPPORTED
        }
        val keyguardUp = pollUntil(KEYGUARD_GONE_TIMEOUT_MS) {
            window.isKeyguardLocked() == true
        }
        if (!keyguardUp) {
            if (window.isKeyguardSecure(0) != true) {
                PrivilegedLog.i("keyguard never appeared and not secure: no lock screen configured")
                return UnlockResult.UNSUPPORTED
            }
            PrivilegedLog.w("keyguard did not lock after lockNow")
            return UnlockResult.LOCK_FAILED
        }

        val off = ensureScreenOff()
        if (!off) {
            PrivilegedLog.w("screen still on after sleep attempts (keyguard already locked)")
        }
        return UnlockResult.OK
    }

    /** 亮屏并解除锁屏；credential 为纯数字 PIN，无密码锁屏传空串。 */
    fun unlock(credential: String): UnlockResult {
        val window = window ?: return UnlockResult.UNSUPPORTED
        val wakeStart = SystemClock.elapsedRealtime()
        wakeAndRequireKeyguard(window)?.let { return it }
        val wakeCost = SystemClock.elapsedRealtime() - wakeStart

        val secure = window.isKeyguardSecure(0) ?: false
        PrivilegedLog.i("keyguard locked, secure=$secure")

        if (!window.dismissKeyguard()) {
            // 部分 ROM 不提供 dismissKeyguard，MENU 至少能把 bouncer 叫出来。
            PrivilegedLog.w("dismissKeyguard unavailable, trying MENU")
            input.injectKey(KeyEvent.KEYCODE_MENU)
            Thread.sleep(MENU_SETTLE_MS)
        }

        if (!secure) {
            return if (pollUntil(KEYGUARD_GONE_TIMEOUT_MS) { keyguardGone(window) }) {
                PrivilegedLog.i("unlocked (insecure keyguard)")
                UnlockResult.OK
            } else {
                PrivilegedLog.w("insecure keyguard did not dismiss")
                UnlockResult.CREDENTIAL_REJECTED
            }
        }

        if (credential.isEmpty()) {
            PrivilegedLog.w("secure keyguard but no credential configured")
            return UnlockResult.CREDENTIAL_REQUIRED
        }
        if (credential.any { !it.isDigit() }) {
            PrivilegedLog.w("only numeric PIN is supported")
            return UnlockResult.CREDENTIAL_REQUIRED
        }

        val settleMs = bouncerSettleMs(wakeCost)
        Thread.sleep(settleMs)
        if (keyguardGone(window)) {
            PrivilegedLog.i("keyguard gone during ${settleMs}ms settle, skip PIN injection")
            return UnlockResult.OK
        }

        PrivilegedLog.i("injecting ${credential.length} PIN digits after ${settleMs}ms settle")
        if (!input.injectPin(credential) { keyguardGone(window) }) {
            return UnlockResult.UNSUPPORTED
        }
        return if (pollUntil(KEYGUARD_GONE_TIMEOUT_MS) { keyguardGone(window) }) {
            PrivilegedLog.i("unlocked (PIN accepted)")
            UnlockResult.OK
        } else {
            // 不重试，避免连续输错触发系统锁定
            PrivilegedLog.w("still locked after PIN injection: wrong PIN, or bouncer was not ready")
            UnlockResult.CREDENTIAL_REJECTED
        }
    }

    /** 亮屏并回放录制的完整锁屏动作；不主动 dismissKeyguard。 */
    fun unlockWithGesture(gestureJson: String): UnlockResult {
        val pattern = UnlockPattern.parseOrNull(gestureJson) ?: run {
            PrivilegedLog.w("no usable gesture recorded")
            return UnlockResult.CREDENTIAL_REQUIRED
        }
        if (pattern.strokes.isEmpty()) return UnlockResult.CREDENTIAL_REQUIRED

        val window = window ?: return UnlockResult.UNSUPPORTED
        wakeAndRequireKeyguard(window)?.let { return it }

        val screen = HiddenDisplayManager.current() ?: run {
            PrivilegedLog.w("display info unavailable")
            return UnlockResult.UNSUPPORTED
        }
        if (screen.rotation != pattern.rotation) {
            PrivilegedLog.w("rotation ${screen.rotation} != recorded ${pattern.rotation}")
            return UnlockResult.PATTERN_SCREEN_MISMATCH
        }
        if (pattern.screenWidth > 0 && pattern.screenHeight > 0 &&
            (screen.width != pattern.screenWidth || screen.height != pattern.screenHeight)
        ) {
            PrivilegedLog.w(
                "screen ${screen.width}x${screen.height} != recorded " +
                    "${pattern.screenWidth}x${pattern.screenHeight}, scaling normalized points",
            )
        }

        // 录制起点就是亮屏后的锁屏首屏，这里不能再 dismissKeyguard 打乱状态。
        Thread.sleep(GESTURE_SETTLE_MS)
        if (keyguardGone(window)) {
            PrivilegedLog.i("keyguard gone during settle, skip gesture replay")
            return UnlockResult.OK
        }

        if (!input.injectGesture(pattern, screen)) {
            PrivilegedLog.w("gesture injection failed (injectInputEvent)")
            return UnlockResult.UNSUPPORTED
        }
        return if (pollUntil(KEYGUARD_GONE_TIMEOUT_MS) { keyguardGone(window) }) {
            PrivilegedLog.i("unlocked (gesture accepted)")
            UnlockResult.OK
        } else {
            PrivilegedLog.w("still locked after gesture replay: trajectory invalid, or keyguard ignored injected events")
            UnlockResult.CREDENTIAL_REJECTED
        }
    }

    fun wakeScreen(): Boolean = ensureScreenOn()

    fun isScreenOn(): Boolean = power?.isScreenOn() ?: false

    /** null 表示 hidden API 不可用；录制端必须把它和「未锁屏」区分开。 */
    fun isKeyguardLocked(): Boolean? = window?.isKeyguardLocked()

    private fun wakeAndRequireKeyguard(window: HiddenWindowManager): UnlockResult? {
        if (!ensureScreenOn()) {
            PrivilegedLog.w("screen did not turn on after wakeUp and key fallback")
            return UnlockResult.WAKE_FAILED
        }
        val locked = window.isKeyguardLocked()
        if (locked == null) {
            PrivilegedLog.w("isKeyguardLocked unavailable")
            return UnlockResult.UNSUPPORTED
        }
        if (!locked) {
            PrivilegedLog.i("keyguard not showing, nothing to do")
            return UnlockResult.ALREADY_UNLOCKED
        }
        return null
    }

    private fun ensureScreenOn(): Boolean {
        val power = power ?: return false
        if (power.isScreenOn()) return true
        power.wakeUp()
        if (pollUntil(SCREEN_ON_TIMEOUT_MS) { power.isScreenOn() }) return true
        PrivilegedLog.w("wakeUp() did not turn screen on, injecting KEYCODE_WAKEUP")
        input.injectKey(KeyEvent.KEYCODE_WAKEUP)
        if (pollUntil(KEY_WAKE_TIMEOUT_MS) { power.isScreenOn() }) return true
        PrivilegedLog.w("KEYCODE_WAKEUP did not turn screen on, injecting KEYCODE_POWER")
        input.injectKey(KeyEvent.KEYCODE_POWER)
        return pollUntil(KEY_WAKE_TIMEOUT_MS) { power.isScreenOn() }
    }

    private fun ensureScreenOff(): Boolean {
        val power = power ?: return false
        if (!power.isScreenOn()) return true
        power.goToSleep()
        if (pollUntil(SCREEN_OFF_TIMEOUT_MS) { !power.isScreenOn() }) return true
        PrivilegedLog.w("goToSleep() did not turn screen off, injecting KEYCODE_SLEEP")
        input.injectKey(KeyEvent.KEYCODE_SLEEP)
        if (pollUntil(KEY_WAKE_TIMEOUT_MS) { !power.isScreenOn() }) return true
        PrivilegedLog.w("KEYCODE_SLEEP did not turn screen off, injecting KEYCODE_POWER")
        input.injectKey(KeyEvent.KEYCODE_POWER)
        return pollUntil(KEY_WAKE_TIMEOUT_MS) { !power.isScreenOn() }
    }

    private fun keyguardGone(window: HiddenWindowManager): Boolean =
        window.isKeyguardLocked() == false

    private fun bouncerSettleMs(wakeCostMs: Long): Long =
        (BOUNCER_SETTLE_MS + wakeCostMs * 2).coerceAtMost(BOUNCER_SETTLE_MAX_MS)

    private fun pollUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return condition()
    }

    @SuppressLint("WakelockTimeout")
    private inline fun <T> keepCpuAwake(timeoutMs: Long, block: () -> T): T {
        val lock = runCatching {
            val pm = context?.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, CPU_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire(timeoutMs)
            }
        }.getOrNull()
        return try {
            block()
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    private companion object {
        const val SCREEN_ON_TIMEOUT_MS = 5_000L
        const val KEY_WAKE_TIMEOUT_MS = 2_000L
        const val SCREEN_OFF_TIMEOUT_MS = 3_000L
        const val KEYGUARD_GONE_TIMEOUT_MS = 5_000L
        const val BOUNCER_SETTLE_MS = 1_200L
        const val BOUNCER_SETTLE_MAX_MS = 5_000L
        const val MENU_SETTLE_MS = 500L
        const val GESTURE_SETTLE_MS = 800L
        const val LOCK_SETTLE_MS = 500L
        const val TEST_CPU_LOCK_MS = 60_000L
        const val POLL_INTERVAL_MS = 100L
        const val CPU_LOCK_TAG = "Izuki:wake-unlock"
    }
}
