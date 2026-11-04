package com.benton.izukijs.privileged.internal

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.benton.izukijs.model.UnlockPattern

/**
 * 特权进程内的输入注入。
 *
 * 触摸走 InputManager.injectInputEvent，而不是写 /dev/input：
 * Android 14+ 对 shell 写 evdev 的 SELinux 限制越来越严，而 Shizuku 以 root 启动时
 * injectInputEvent 是稳定路径。
 */
internal class PrivilegedInput(context: Context?) {

    private val manager = HiddenInputManager.create(context)

    fun injectKey(keyCode: Int): Boolean {
        val down = manager?.inject(
            KeyEvent(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_DOWN,
                keyCode,
                0,
            ),
            HiddenInputManager.MODE_WAIT_FOR_FINISH,
        ) ?: false
        val up = manager?.inject(
            KeyEvent(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyCode,
                0,
            ),
            HiddenInputManager.MODE_ASYNC,
        ) ?: false
        if (down && up) return true
        return injectKeyViaShell(keyCode)
    }

    /**
     * 回放整段手势。每个点之间的间隔取录制时的相对时间；旧数据没有时间时按 duration 均分。
     * 单点笔画按点击处理，长按用 durationMs 保底。
     */
    fun injectGesture(pattern: UnlockPattern, screen: ScreenGeometry): Boolean {
        if (pattern.strokes.isEmpty()) return false
        for (stroke in pattern.strokes) {
            val points = stroke.points
            if (points.isEmpty()) continue
            val downTime = SystemClock.uptimeMillis()
            if (points.size == 1) {
                val x = points[0].x * screen.width
                val y = points[0].y * screen.height
                if (!injectTouch(downTime, MotionEvent.ACTION_DOWN, x, y, 1f, wait = true)) {
                    return false
                }
                val hold = stroke.durationMs
                    .takeIf { it > 0L }
                    ?.coerceIn(TAP_HOLD_MS, MAX_HOLD_MS)
                    ?: TAP_HOLD_MS
                Thread.sleep(hold)
                if (!injectTouch(downTime, MotionEvent.ACTION_UP, x, y, 0f, wait = false)) {
                    return false
                }
            } else {
                val first = points.first()
                if (!injectTouch(
                        downTime,
                        MotionEvent.ACTION_DOWN,
                        first.x * screen.width,
                        first.y * screen.height,
                        1f,
                        wait = true,
                    )
                ) {
                    return false
                }
                for (index in 1 until points.size) {
                    val delta = pointDeltaMs(points, index, stroke.durationMs)
                    if (delta > 0) Thread.sleep(delta)
                    val point = points[index]
                    val up = index == points.lastIndex
                    if (!injectTouch(
                            downTime,
                            if (up) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE,
                            point.x * screen.width,
                            point.y * screen.height,
                            if (up) 0f else 1f,
                            wait = false,
                        )
                    ) {
                        return false
                    }
                }
            }
            Thread.sleep(STROKE_GAP_MS)
        }
        return true
    }

    /** 逐位注入 PIN；每个键前确认 keyguard 还在，返回 false 表示注入通路失败。 */
    fun injectPin(digits: String, keyguardGone: () -> Boolean): Boolean {
        for (digit in digits) {
            if (keyguardGone()) return true
            if (!injectKey(KeyEvent.KEYCODE_0 + (digit - '0'))) return false
            Thread.sleep(PIN_DIGIT_GAP_MS)
        }
        if (keyguardGone()) return true
        return injectKey(KeyEvent.KEYCODE_ENTER)
    }

    private fun injectTouch(
        downTime: Long,
        action: Int,
        x: Float,
        y: Float,
        pressure: Float,
        wait: Boolean,
    ): Boolean {
        val inputManager = manager ?: return false
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x.coerceAtLeast(0f)
            this.y = y.coerceAtLeast(0f)
            this.pressure = pressure
            size = 1f
        }
        val event = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            1,
            arrayOf(properties),
            arrayOf(coords),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
        return try {
            inputManager.inject(
                event,
                if (wait) HiddenInputManager.MODE_WAIT_FOR_FINISH else HiddenInputManager.MODE_ASYNC,
            )
        } finally {
            event.recycle()
        }
    }

    private fun pointDeltaMs(
        points: List<com.benton.izukijs.model.PatternPoint>,
        index: Int,
        durationMs: Long,
    ): Long {
        val recorded = points[index].timeMs - points[index - 1].timeMs
        val fallback = if (durationMs > 0L && points.size > 1) {
            durationMs / (points.size - 1)
        } else {
            DEFAULT_POINT_GAP_MS
        }
        return (if (recorded > 0L) recorded else fallback).coerceIn(1L, MAX_POINT_GAP_MS)
    }

    private fun injectKeyViaShell(keyCode: Int): Boolean = runCatching {
        val process = ProcessBuilder("/system/bin/input", "keyevent", keyCode.toString())
            .redirectErrorStream(true)
            .start()
        process.waitFor()
        process.exitValue() == 0
    }.getOrDefault(false)

    private companion object {
        const val TAP_HOLD_MS = 60L
        const val MAX_HOLD_MS = 3_000L
        const val PIN_DIGIT_GAP_MS = 50L
        const val STROKE_GAP_MS = 180L
        const val DEFAULT_POINT_GAP_MS = 12L
        const val MAX_POINT_GAP_MS = 50L
    }
}
