package com.benton.izukijs.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.benton.izukijs.IzukiApp
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.runtime.RunControl
import com.benton.izukijs.di.AppContainer

/** 与 [KeyEvent.KEYCODE_VOLUME_UP] 对齐，纯逻辑层不直接引用 android.view。 */
internal const val VOLUME_KEY_UP = 24

/** 与 [KeyEvent.KEYCODE_VOLUME_DOWN] 对齐。 */
internal const val VOLUME_KEY_DOWN = 25

internal fun isVolumeKey(keyCode: Int): Boolean =
    keyCode == VOLUME_KEY_UP || keyCode == VOLUME_KEY_DOWN

internal fun QuickStopKey.matchesVolumeKey(keyCode: Int): Boolean = when (this) {
    QuickStopKey.VOLUME_DOWN -> keyCode == VOLUME_KEY_DOWN
    QuickStopKey.VOLUME_UP -> keyCode == VOLUME_KEY_UP
    QuickStopKey.BOTH -> isVolumeKey(keyCode)
}

/** 状态机输出；[STOP] 也代表消费按键。 */
internal enum class QuickStopEffect {
    IGNORE,
    CONSUME,
    STOP,
    CONSUME_AND_ARM_LONG_PRESS,
    CONSUME_AND_CANCEL_LONG_PRESS,
}

/**
 * 音量键快速停止的按压状态机。
 *
 * 只处理已通过「开关 / 运行中 / 真实按键 / 非注入」过滤的事件。长按计时由调用方调度，
 * 这样状态机本身可以脱离 Android 框架在 JVM 单测中推进时间。
 */
internal class QuickStopGestureTracker(
    private val doublePressWindowMs: Long = 600L,
) {

    private var consumedKey = 0
    private var firstDownAt = 0L
    private var firstKey = 0
    private var waitingSecond = false
    private var longPressArmedKey = 0
    private var longPressTriggered = false

    fun onKeyDown(
        keyCode: Int,
        repeatCount: Int,
        gesture: QuickStopGesture,
        nowMs: Long,
    ): QuickStopEffect {
        if (consumedKey != 0) {
            if (keyCode != consumedKey) return QuickStopEffect.IGNORE
            // 系统抬键事件可能丢失；同一键的新按下（repeatCount=0）按新按压处理。
            if (repeatCount == 0) clearPressed() else return QuickStopEffect.CONSUME
        }
        if (repeatCount > 0) return QuickStopEffect.IGNORE

        return when (gesture) {
            QuickStopGesture.SINGLE -> {
                consumedKey = keyCode
                QuickStopEffect.STOP
            }

            QuickStopGesture.DOUBLE -> {
                consumedKey = keyCode
                if (waitingSecond && keyCode == firstKey &&
                    nowMs - firstDownAt <= doublePressWindowMs
                ) {
                    waitingSecond = false
                    firstKey = 0
                    QuickStopEffect.STOP
                } else {
                    waitingSecond = true
                    firstKey = keyCode
                    firstDownAt = nowMs
                    QuickStopEffect.CONSUME
                }
            }

            QuickStopGesture.LONG -> {
                consumedKey = keyCode
                longPressArmedKey = keyCode
                longPressTriggered = false
                QuickStopEffect.CONSUME_AND_ARM_LONG_PRESS
            }
        }
    }

    fun onKeyUp(keyCode: Int): QuickStopEffect {
        if (consumedKey == 0 || keyCode != consumedKey) return QuickStopEffect.IGNORE
        val wasArmed = longPressArmedKey == keyCode
        clearPressed()
        return if (wasArmed) {
            QuickStopEffect.CONSUME_AND_CANCEL_LONG_PRESS
        } else {
            QuickStopEffect.CONSUME
        }
    }

    fun onLongPressTimeout(keyCode: Int): QuickStopEffect {
        if (consumedKey == 0 || consumedKey != keyCode) return QuickStopEffect.IGNORE
        if (longPressArmedKey != keyCode || longPressTriggered) return QuickStopEffect.IGNORE
        longPressTriggered = true
        return QuickStopEffect.STOP
    }

    fun reset() {
        clearPressed()
        waitingSecond = false
        firstKey = 0
        firstDownAt = 0L
    }

    private fun clearPressed() {
        consumedKey = 0
        longPressArmedKey = 0
        longPressTriggered = false
    }
}

/**
 * 进程级音量键快速停止入口。
 *
 * 主通道由无障碍服务过滤按键，命中时消费事件，因此不会改变系统音量；无障碍未开启时
 * 由脚本前台服务监听音量变化作为单击降级。两条通道共用停止去重与注入抑制。
 */
internal object VolumeKeyQuickStop {

    private const val DOUBLE_PRESS_WINDOW_MS = 600L
    private const val LONG_PRESS_MS = 800L
    private const val STOP_DEDUP_MS = 1_000L
    private const val INJECTED_SUPPRESS_MS = 500L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tracker = QuickStopGestureTracker(DOUBLE_PRESS_WINDOW_MS)

    @Volatile
    private var injectedAtMs = 0L

    @Volatile
    private var lastStopAtMs = 0L

    private var longPressRunnable: Runnable? = null

    /** 记录脚本注入的音量键，避免 `press()` 触发快速停止或降级监听。 */
    fun noteInjectedKey(keyCode: Int) {
        if (isVolumeKey(keyCode)) {
            injectedAtMs = SystemClock.elapsedRealtime()
        }
    }

    /** 无障碍按键回调；返回 true 表示消费该按键，系统不再处理。 */
    fun onAccessibilityKey(context: Context, event: KeyEvent): Boolean {
        val container = containerOf(context) ?: return false
        val settings = container.runtimeSettingsRepository.current()
        if (!settings.volumeKeyStopEnabled) {
            reset()
            return false
        }
        if (!settings.volumeKeyStopKey.matchesVolumeKey(event.keyCode)) return false

        val keyCode = event.keyCode
        val now = SystemClock.elapsedRealtime()

        // 注入按键的 deviceId 为 -1；这里只响应真实物理按键。
        if (event.deviceId < 0) return false

        if (event.action == KeyEvent.ACTION_UP) {
            val effect = tracker.onKeyUp(keyCode)
            if (effect != QuickStopEffect.IGNORE) cancelLongPress()
            return effect != QuickStopEffect.IGNORE
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false

        if (now - injectedAtMs < INJECTED_SUPPRESS_MS) return false

        if (!container.runControl.running.value) {
            reset()
            return false
        }

        return when (
            tracker.onKeyDown(
                keyCode = keyCode,
                repeatCount = event.repeatCount,
                gesture = settings.volumeKeyStopGesture,
                nowMs = now,
            )
        ) {
            QuickStopEffect.IGNORE -> false
            QuickStopEffect.CONSUME -> true
            QuickStopEffect.STOP -> {
                cancelLongPress()
                requestStop(container.runControl, container.logBus)
                true
            }

            QuickStopEffect.CONSUME_AND_ARM_LONG_PRESS -> {
                armLongPress(container.runControl, container.logBus, keyCode)
                true
            }

            QuickStopEffect.CONSUME_AND_CANCEL_LONG_PRESS -> true
        }
    }

    /**
     * 无障碍未开启时的降级通道：只能按音量变化方向判断单击。
     *
     * 双击 / 长按依赖按键按下 / 抬起时刻，仅凭音量变化无法可靠区分，因此不做降级。
     */
    fun onVolumeChanged(
        context: Context,
        previous: Int,
        current: Int,
        accessibilityConnected: Boolean,
    ) {
        if (accessibilityConnected) return
        val container = containerOf(context) ?: return
        val settings = container.runtimeSettingsRepository.current()
        if (!settings.volumeKeyStopEnabled) return
        if (settings.volumeKeyStopGesture != QuickStopGesture.SINGLE) return
        if (previous < 0 || current < 0 || current == previous) return

        val now = SystemClock.elapsedRealtime()
        if (now - injectedAtMs < INJECTED_SUPPRESS_MS) return
        if (!container.runControl.running.value) return

        val keyCode = if (current > previous) VOLUME_KEY_UP else VOLUME_KEY_DOWN
        if (!settings.volumeKeyStopKey.matchesVolumeKey(keyCode)) return
        requestStop(container.runControl, container.logBus)
    }

    fun reset() {
        cancelLongPress()
        tracker.reset()
    }

    private fun armLongPress(runControl: RunControl, logBus: LogBus, keyCode: Int) {
        cancelLongPress()
        val runnable = Runnable {
            longPressRunnable = null
            if (tracker.onLongPressTimeout(keyCode) != QuickStopEffect.STOP) return@Runnable
            if (runControl.running.value) requestStop(runControl, logBus)
        }
        longPressRunnable = runnable
        mainHandler.postDelayed(runnable, LONG_PRESS_MS)
    }

    private fun cancelLongPress() {
        longPressRunnable?.let(mainHandler::removeCallbacks)
        longPressRunnable = null
    }

    private fun requestStop(runControl: RunControl, logBus: LogBus) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStopAtMs < STOP_DEDUP_MS) return
        if (!runControl.running.value) return
        lastStopAtMs = now
        logBus.info("⌨ 音量键快速停止")
        runControl.requestStop()
    }

    private fun containerOf(context: Context): AppContainer? =
        (context.applicationContext as? IzukiApp)?.container
}
