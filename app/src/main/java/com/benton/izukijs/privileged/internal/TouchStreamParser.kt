package com.benton.izukijs.privileged.internal

internal data class TouchPoint(val x: Int, val y: Int, val tMs: Int)

internal data class TouchStroke(val points: List<TouchPoint>, val endTMs: Int)

/**
 * evdev 事件流 -> 单指轨迹。
 *
 * 只跟主触点：PIN、图案、上滑都是单指，多指对解锁没有意义。
 * 兼容 MT 协议 B（ABS_MT_TRACKING_ID）、协议 A（SYN_MT_REPORT）与老式单点（BTN_TOUCH + ABS_X/Y）。
 */
internal class TouchStreamParser {

    private val _strokes = mutableListOf<TouchStroke>()
    val strokes: List<TouchStroke> get() = _strokes

    /**
     * 连续录制模式：每段轨迹闭合就回调，不保留历史，避免 [MAX_STROKES] 上限。
     * 解锁录制仍使用默认模式，结束后一次性读取 [strokes]。
     */
    var streaming: Boolean = false

    var onStrokeClosed: ((TouchStroke) -> Unit)? = null

    private var slot = 0
    private var primarySlot = NO_SLOT
    private var usesTrackingId = false
    private var btnTouch = false
    private var usesBtnTouch = false
    private var usesMt = false

    private val slotX = HashMap<Int, Int>()
    private val slotY = HashMap<Int, Int>()

    private var singleX = -1
    private var singleY = -1
    private var frameHasCoords = false

    private var mtFrameIndex = 0
    private var current: MutableList<TouchPoint>? = null

    fun onEvent(type: Int, code: Int, value: Int, tMs: Int) {
        when (type) {
            EV_ABS -> onAbs(code, value)
            EV_KEY -> if (code == BTN_TOUCH) {
                usesBtnTouch = true
                btnTouch = value != 0
            }
            EV_SYN -> when (code) {
                SYN_MT_REPORT -> mtFrameIndex++
                SYN_REPORT -> {
                    commitFrame(tMs)
                    frameHasCoords = false
                    mtFrameIndex = 0
                }
            }
        }
    }

    fun finish(tMs: Int) = closeStroke(tMs)

    private fun onAbs(code: Int, value: Int) {
        when (code) {
            ABS_MT_SLOT -> slot = value

            ABS_MT_TRACKING_ID -> {
                usesTrackingId = true
                if (value >= 0) {
                    if (primarySlot == NO_SLOT) primarySlot = slot
                } else if (slot == primarySlot) {
                    primarySlot = NO_SLOT
                }
            }

            ABS_MT_POSITION_X -> {
                usesMt = true
                if (mtFrameIndex == 0) slotX[slot] = value
                if (isPrimaryContact()) frameHasCoords = true
            }

            ABS_MT_POSITION_Y -> {
                usesMt = true
                if (mtFrameIndex == 0) slotY[slot] = value
                if (isPrimaryContact()) frameHasCoords = true
            }

            ABS_X -> if (!usesMt) {
                singleX = value
                frameHasCoords = true
            }

            ABS_Y -> if (!usesMt) {
                singleY = value
                frameHasCoords = true
            }
        }
    }

    private fun isPrimaryContact() = mtFrameIndex == 0 &&
        if (usesTrackingId) slot == primarySlot else slot == 0

    private fun commitFrame(tMs: Int) {
        val down = when {
            usesTrackingId -> primarySlot != NO_SLOT
            usesBtnTouch -> btnTouch
            else -> frameHasCoords
        }
        if (!down) {
            closeStroke(tMs)
            return
        }
        val contactSlot = if (usesTrackingId) primarySlot else 0
        val x = if (usesMt) slotX[contactSlot] ?: -1 else singleX
        val y = if (usesMt) slotY[contactSlot] ?: -1 else singleY
        if (x >= 0 && y >= 0) appendPoint(x, y, tMs)
    }

    private fun appendPoint(x: Int, y: Int, tMs: Int) {
        val points = current ?: mutableListOf<TouchPoint>().also { current = it }
        if (points.size >= MAX_POINTS_PER_STROKE) return
        val last = points.lastOrNull()
        if (last != null && last.x == x && last.y == y) return
        points.add(TouchPoint(x, y, tMs))
    }

    private fun closeStroke(tMs: Int) {
        val points = current
        current = null
        if (points == null || points.isEmpty()) return
        val stroke = TouchStroke(points, tMs.coerceAtLeast(points.last().tMs))
        if (streaming) {
            onStrokeClosed?.invoke(stroke)
            return
        }
        if (_strokes.size >= MAX_STROKES) return
        _strokes.add(stroke)
    }

    companion object {
        const val EV_SYN = 0x00
        const val EV_KEY = 0x01
        const val EV_ABS = 0x03

        const val SYN_REPORT = 0x00
        const val SYN_MT_REPORT = 0x02

        const val BTN_TOUCH = 0x14a

        const val ABS_X = 0x00
        const val ABS_Y = 0x01
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39

        const val MAX_POINTS_PER_STROKE = 4000
        const val MAX_STROKES = 128

        private const val NO_SLOT = -1
    }
}
