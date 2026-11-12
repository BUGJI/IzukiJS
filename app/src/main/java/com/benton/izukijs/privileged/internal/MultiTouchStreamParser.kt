package com.benton.izukijs.privileged.internal

/** 一次多指手势：包含每个触点的完整轨迹与最大同时触点数。 */
internal data class TouchGesture(
    val strokes: List<TouchStroke>,
    val pointerCount: Int,
)

/**
 * evdev 事件流 -> 多指手势。
 *
 * 与 [TouchStreamParser] 的区别：后者只跟随主触点，适合解锁图案；
 * 这里跟踪所有 slot，等所有触点抬起后把同一时间段内的轨迹作为一次手势输出。
 *
 * 只实现 Protocol B（ABS_MT_SLOT + ABS_MT_TRACKING_ID）；其他协议回退到
 * [TouchStreamParser] 的单指路径，由 [InteractionRecorder] 选择。
 */
internal class MultiTouchStreamParser {

    var onGestureClosed: ((TouchGesture) -> Unit)? = null

    private val slots = LinkedHashMap<Int, Slot>()
    private var currentSlot = 0
    private var maxPointers = 0
    private var strokes = ArrayList<TouchStroke>()

    fun onEvent(type: Int, code: Int, value: Int, tMs: Int) {
        when (type) {
            EV_ABS -> onAbs(code, value, tMs)
            EV_KEY -> if (code == BTN_TOUCH && value == 0) closeAll(tMs)
            EV_SYN -> if (code == SYN_REPORT) commitFrame(tMs)
        }
    }

    fun finish(tMs: Int) {
        closeAll(tMs)
    }

    private fun onAbs(code: Int, value: Int, tMs: Int) {
        when (code) {
            ABS_MT_SLOT -> currentSlot = value

            ABS_MT_TRACKING_ID -> {
                if (value >= 0) {
                    slots.remove(currentSlot)?.let { closeSlot(it, tMs) }
                    slots[currentSlot] = Slot()
                    if (slots.size > maxPointers) maxPointers = slots.size
                } else {
                    slots.remove(currentSlot)?.let { closeSlot(it, tMs) }
                    if (slots.isEmpty()) emitGesture(tMs)
                }
            }

            ABS_MT_POSITION_X -> slots[currentSlot]?.x = value
            ABS_MT_POSITION_Y -> slots[currentSlot]?.y = value
        }
    }

    private fun commitFrame(tMs: Int) {
        for (slot in slots.values) {
            val x = slot.x
            val y = slot.y
            if (x < 0 || y < 0) continue
            val last = slot.points.lastOrNull()
            if (last != null && last.x == x && last.y == y) continue
            slot.points.add(TouchPoint(x, y, tMs))
        }
    }

    private fun closeSlot(slot: Slot, tMs: Int) {
        val points = slot.points
        if (points.isEmpty()) return
        val end = tMs.coerceAtLeast(points.last().tMs)
        strokes.add(TouchStroke(points.toList(), end))
    }

    private fun closeAll(tMs: Int) {
        if (slots.isEmpty()) {
            emitGesture(tMs)
            return
        }
        slots.values.toList().forEach { closeSlot(it, tMs) }
        slots.clear()
        emitGesture(tMs)
    }

    private fun emitGesture(tMs: Int) {
        if (strokes.isEmpty()) {
            reset(tMs)
            return
        }
        val gesture = TouchGesture(
            strokes = strokes.sortedBy { it.points.first().tMs },
            pointerCount = maxPointers.coerceAtLeast(1),
        )
        strokes = ArrayList()
        reset(tMs)
        onGestureClosed?.invoke(gesture)
    }

    private fun reset(tMs: Int) {
        maxPointers = 0
        currentSlot = 0
    }

    private class Slot {
        var x: Int = -1
        var y: Int = -1
        val points = ArrayList<TouchPoint>()
    }

    private companion object {
        const val EV_SYN = 0x00
        const val EV_KEY = 0x01
        const val EV_ABS = 0x03

        const val SYN_REPORT = 0x00
        const val BTN_TOUCH = 0x14a

        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39
    }
}
