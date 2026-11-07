package com.benton.izukijs.model

/** `getevent -lp` 中识别出的触摸设备。 */
data class TouchInputDevice(
    val path: String,
    val maxX: Int,
    val maxY: Int,
    val minX: Int = 0,
    val minY: Int = 0,
    val name: String = "",
    val hasSlot: Boolean = false,
    val hasTrackingId: Boolean = false,
    val hasBtnTouch: Boolean = false,
    val hasMtReport: Boolean = false,
    val isDirect: Boolean = false,
    val hasStylus: Boolean = false,
    val hasTouchMajor: Boolean = false,
    val hasPressure: Boolean = false,
    val pressureCode: Int = 0x3a,
    val pressureMax: Int = 0,
) {
    val rangeX: Int get() = maxX - minX + 1

    val rangeY: Int get() = maxY - minY + 1

    val usable: Boolean get() = rangeX > 1 && rangeY > 1

    val protocolB: Boolean get() = hasSlot && hasTrackingId

    val displayName: String get() = if (name.isBlank()) path else "\"$name\""
}

/**
 * 解析 `getevent` 的文本输出。
 *
 * 设备选择参考 MaaEnd 的做法：排除手写笔、优先 Protocol B / INPUT_PROP_DIRECT / BTN_TOUCH，
 * 并保留 min/max 用于归一化。事件解析同时兼容 Protocol A（BTN_TOUCH + ABS_X/Y）与
 * Protocol B（ABS_MT_SLOT + TRACKING_ID），只跟随第一根按下的手指。
 */
object UnlockRecordingParser {

    private val DEVICE_LINE = Regex("""^add device \d+:\s*(/dev/input/event\d+)\s*$""")
    private val NAME_LINE = Regex("""^\s*name:\s*"(.*)"\s*$""")
    private val SECTION_LINE = Regex("""^\s*(ABS|KEY|SYN|SW|REL)\s*\(([0-9a-fA-F]{4})\):\s*(.*)$""")
    private val ABS_CODE_PREFIX = Regex("""^\s*([0-9a-fA-F]{4}|ABS_[A-Z0-9_]+)\s*:""")
    private val ABS_RANGE = Regex("""min\s+(-?\d+),\s*max\s+(-?\d+)""")
    private val KEY_HEX = Regex("""\b[0-9a-fA-F]{4}\b""")
    private val TIMESTAMP_PREFIX = Regex("""^\s*\[\s*([0-9.]+)\]\s*""")
    private val DEVICE_PREFIX = Regex("""^\s*(/dev/input/event\d+)\s*:\s*""")
    private val SHORT_DEVICE_PREFIX = Regex("""^\s*(event\d+)\s*:\s*""")

    private const val EV_SYN = 0x00
    private const val EV_KEY = 0x01
    private const val EV_ABS = 0x03

    private const val SYN_REPORT = 0x00
    private const val SYN_MT_REPORT = 0x02

    private const val ABS_X = 0x00
    private const val ABS_Y = 0x01
    private const val ABS_PRESSURE = 0x18
    private const val ABS_MT_SLOT = 0x2f
    private const val ABS_MT_TOUCH_MAJOR = 0x30
    private const val ABS_MT_POSITION_X = 0x35
    private const val ABS_MT_POSITION_Y = 0x36
    private const val ABS_MT_TRACKING_ID = 0x39
    private const val ABS_MT_PRESSURE = 0x3a

    private const val BTN_TOOL_PEN = 0x140
    private const val BTN_TOUCH = 0x14a

    private val LABEL_TO_CODE = mapOf(
        "ABS_X" to ABS_X,
        "ABS_Y" to ABS_Y,
        "ABS_MT_SLOT" to ABS_MT_SLOT,
        "ABS_MT_POSITION_X" to ABS_MT_POSITION_X,
        "ABS_MT_POSITION_Y" to ABS_MT_POSITION_Y,
        "ABS_MT_TRACKING_ID" to ABS_MT_TRACKING_ID,
        "BTN_TOOL_PEN" to BTN_TOOL_PEN,
        "BTN_TOUCH" to BTN_TOUCH,
        "SYN_REPORT" to SYN_REPORT,
        "SYN_MT_REPORT" to SYN_MT_REPORT,
    )

    /** 返回按优先级排序、可用的触摸设备。 */
    fun parseTouchDevices(output: String): List<TouchInputDevice> {
        val devices = mutableListOf<DeviceBuilder>()
        var current: DeviceBuilder? = null
        var section: String? = null

        fun flush() {
            current?.let { devices += it }
            current = null
            section = null
        }

        output.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            val trimmed = line.trim()

            DEVICE_LINE.find(trimmed)?.let { match ->
                flush()
                current = DeviceBuilder(match.groupValues[1])
                return@forEach
            }
            val device = current ?: return@forEach

            NAME_LINE.find(trimmed)?.let { match ->
                device.name = match.groupValues[1]
                section = null
                return@forEach
            }

            if (trimmed == "events:") {
                section = null
                return@forEach
            }
            if (trimmed.startsWith("input props:")) {
                section = "PROPS"
                return@forEach
            }

            val sectionMatch = SECTION_LINE.find(trimmed)
            if (sectionMatch != null) {
                section = sectionMatch.groupValues[1]
                device.consume(section, sectionMatch.groupValues[3])
                return@forEach
            }

            if (trimmed.isEmpty()) return@forEach
            device.consume(section, trimmed)
        }
        flush()

        return devices
            .filter { it.hasTouchRange && !it.stylus }
            .map { it.build() }
            .filter { it.usable }
            .sortedByDescending(::score)
    }

    /** 旧调用点保留：返回优先级最高的一个设备。 */
    fun parseTouchDevice(output: String): TouchInputDevice? = parseTouchDevices(output).firstOrNull()

    fun parseStrokes(
        output: String,
        device: TouchInputDevice,
    ): List<UnlockStroke> {
        if (!device.usable) return emptyList()
        val strokes = mutableListOf<UnlockStroke>()
        var usesMt = device.hasSlot || device.hasTrackingId || device.hasMtReport
        val slotX = mutableMapOf<Int, Int>()
        val slotY = mutableMapOf<Int, Int>()
        var singleX: Int? = null
        var singleY: Int? = null
        var currentSlot = 0
        var primarySlot: Int? = null
        var down = false
        var strokeStartTs = 0L
        var lastTs = 0L
        var firstTimestamp = -1L
        var current = mutableListOf<PatternPoint>()

        fun coordinate(slot: Int): Pair<Int, Int>? {
            val x = if (usesMt) slotX[slot] else singleX
            val y = if (usesMt) slotY[slot] else singleY
            return if (x != null && y != null) x to y else null
        }

        fun appendPoint(slot: Int, timestamp: Long) {
            val (x, y) = coordinate(slot) ?: return
            val point = PatternPoint(
                x = normalize(x, device.minX, device.maxX),
                y = normalize(y, device.minY, device.maxY),
                timeMs = (timestamp - strokeStartTs).coerceAtLeast(0L),
            )
            val last = current.lastOrNull()
            if (last == null || last.x != point.x || last.y != point.y) {
                current.add(point)
            }
        }

        fun finishStroke(timestamp: Long) {
            if (!down) return
            appendPoint(primarySlot ?: currentSlot, timestamp)
            if (current.isNotEmpty()) {
                strokes += UnlockStroke(
                    points = current.toList(),
                    startTimeMs = (strokeStartTs - firstTimestamp).coerceAtLeast(0L),
                    durationMs = (timestamp - strokeStartTs).coerceAtLeast(1L),
                )
            }
            current = mutableListOf()
            down = false
            primarySlot = null
        }

        fun startStroke(slot: Int, timestamp: Long) {
            if (down) return
            down = true
            primarySlot = slot
            strokeStartTs = timestamp
            current = mutableListOf()
        }

        output.lineSequence().forEach { line ->
            var rest = line
            val timestamp = TIMESTAMP_PREFIX.find(rest)?.let { match ->
                rest = rest.substring(match.range.last + 1)
                timestampOf(match.groupValues[1])
            } ?: lastTs
            lastTs = timestamp
            if (firstTimestamp < 0) firstTimestamp = timestamp

            // `getevent -t <device>`（只监听一个设备）在部分 toybox 版本不会打印设备前缀，
            // 只有监听全部设备时才带 `/dev/input/eventN:`。两种格式都要兼容。
            val deviceMatch = DEVICE_PREFIX.find(rest)
            if (deviceMatch != null) {
                if (!sameDevice(deviceMatch.groupValues[1], device.path)) return@forEach
                rest = rest.substring(deviceMatch.range.last + 1)
            } else {
                val shortMatch = SHORT_DEVICE_PREFIX.find(rest)
                if (shortMatch != null) {
                    if (!sameDevice(shortMatch.groupValues[1], device.path)) return@forEach
                    rest = rest.substring(shortMatch.range.last + 1)
                }
            }

            val event = parseEvent(rest) ?: return@forEach

            when (event.type) {
                EV_ABS -> when (event.code) {
                    ABS_MT_SLOT -> {
                        usesMt = true
                        currentSlot = event.value.toInt()
                    }

                    ABS_MT_POSITION_X -> {
                        usesMt = true
                        slotX[currentSlot] = event.value.toInt()
                    }

                    ABS_MT_POSITION_Y -> {
                        usesMt = true
                        slotY[currentSlot] = event.value.toInt()
                    }

                    ABS_X -> if (!usesMt) singleX = event.value.toInt()
                    ABS_Y -> if (!usesMt) singleY = event.value.toInt()
                    ABS_MT_TRACKING_ID -> {
                        usesMt = true
                        if (event.value >= 0L) {
                            if (!down) startStroke(currentSlot, timestamp)
                        } else if (primarySlot == currentSlot) {
                            finishStroke(timestamp)
                        }
                    }
                }

                EV_KEY -> if (event.code == BTN_TOUCH) {
                    if (event.value != 0L) {
                        val slot = if (usesMt) currentSlot else 0
                        if (!down) startStroke(slot, timestamp)
                    } else if (primarySlot == currentSlot || primarySlot == 0 || !usesMt) {
                        finishStroke(timestamp)
                    }
                }

                EV_SYN -> if (down) {
                    appendPoint(primarySlot ?: currentSlot, timestamp)
                }
            }
        }
        finishStroke(lastTs)
        return strokes
    }

    private fun sameDevice(linePath: String, devicePath: String): Boolean =
        linePath == devicePath || linePath.substringAfterLast('/') == devicePath.substringAfterLast('/')

    private fun normalize(value: Int, min: Int, max: Int): Float {
        val range = max - min + 1
        if (range <= 1) return 0f
        return ((value - min).toFloat() / range).coerceIn(0f, 1f)
    }

    private fun score(device: TouchInputDevice): Long {
        var score = device.rangeX.toLong() * device.rangeY
        if (device.hasTrackingId) score += 100_000_000_000L
        if (device.isDirect) score += 8_000_000_000L
        if (device.hasBtnTouch) score += 4_000_000_000L
        if (device.hasSlot) score += 1_000_000_000L
        if (device.protocolB) score += 500_000_000L
        if (device.name.contains("touch", ignoreCase = true)) score += 100_000_000L
        return score
    }

    private fun timestampOf(raw: String?): Long? {
        val value = raw?.toDoubleOrNull() ?: return null
        return (value * 1000.0).toLong()
    }

    private data class RawEvent(val type: Int, val code: Int, val value: Long)

    private fun parseEvent(body: String): RawEvent? {
        val parts = body.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null

        val first = parts[0]
        if (first.startsWith("EV_")) {
            val type = typeCode(first) ?: return null
            val code = codeCode(parts.getOrNull(1) ?: return null) ?: return null
            return RawEvent(type, code, parseValue(parts.getOrNull(2)))
        }

        val numericType = first.toIntOrNull(16)
        if (numericType != null) {
            val code = parts.getOrNull(1)?.toIntOrNull(16) ?: return null
            return RawEvent(numericType, code, parseValue(parts.getOrNull(2)))
        }

        val code = codeCode(first) ?: return null
        val inferredType = when {
            first.startsWith("SYN_") -> EV_SYN
            first.startsWith("BTN_") -> EV_KEY
            first.startsWith("ABS_") -> EV_ABS
            else -> typeForCode(code)
        }
        return RawEvent(inferredType, code, parseValue(parts.getOrNull(1)))
    }

    private fun typeCode(label: String): Int? = when (label) {
        "EV_SYN" -> EV_SYN
        "EV_KEY" -> EV_KEY
        "EV_REL" -> 0x02
        "EV_ABS" -> EV_ABS
        "EV_MSC" -> 0x04
        "EV_SW" -> 0x05
        else -> null
    }

    private fun typeForCode(code: Int): Int = when (code) {
        SYN_REPORT, SYN_MT_REPORT -> EV_SYN
        BTN_TOUCH, BTN_TOOL_PEN -> EV_KEY
        else -> EV_ABS
    }

    private fun codeCode(raw: String): Int? =
        LABEL_TO_CODE[raw] ?: raw.takeIf { it.length == 4 }?.toIntOrNull(16)

    private fun parseValue(raw: String?): Long {
        val text = raw?.trim()?.removePrefix("0x")?.removePrefix("0X") ?: return 0L
        when (text.uppercase()) {
            "DOWN" -> return 1L
            "UP" -> return 0L
            "REPEAT" -> return 2L
        }
        val value = text.toLongOrNull(16) ?: text.toLongOrNull(10) ?: return 0L
        return if (text.length >= 8 && value > Int.MAX_VALUE) {
            value - 0x1_0000_0000L
        } else {
            value
        }
    }

    private class DeviceBuilder(val path: String) {
        var name: String = ""
        var stylus = false
        var direct = false
        var hasSlot = false
        var hasTrackingId = false
        var hasBtnTouch = false
        var hasMtReport = false

        private var mtX: IntRange? = null
        private var mtY: IntRange? = null
        private var singleX: IntRange? = null
        private var singleY: IntRange? = null
        private var touchMajor: IntRange? = null
        private var mtPressure: IntRange? = null
        private var singlePressure: IntRange? = null

        val hasTouchRange: Boolean
            get() = (mtX != null && mtY != null) || (singleX != null && singleY != null)

        fun consume(section: String?, content: String) {
            when (section) {
                "PROPS" -> if (content.contains("INPUT_PROP_DIRECT")) direct = true

                "ABS" -> {
                    val codeToken = ABS_CODE_PREFIX.find(content)?.groupValues?.get(1) ?: return
                    val code = LABEL_TO_CODE[codeToken] ?: codeToken.toIntOrNull(16) ?: return
                    val range = ABS_RANGE.find(content) ?: return
                    val min = range.groupValues[1].toIntOrNull() ?: return
                    val max = range.groupValues[2].toIntOrNull() ?: return
                    when (code) {
                        ABS_MT_POSITION_X -> mtX = min..max
                        ABS_MT_POSITION_Y -> mtY = min..max
                        ABS_X -> singleX = min..max
                        ABS_Y -> singleY = min..max
                        ABS_MT_SLOT -> hasSlot = true
                        ABS_MT_TRACKING_ID -> hasTrackingId = true
                        ABS_MT_TOUCH_MAJOR -> touchMajor = min..max
                        ABS_MT_PRESSURE -> mtPressure = min..max
                        ABS_PRESSURE -> singlePressure = min..max
                    }
                }

                "KEY" -> {
                    if (content.contains("BTN_TOUCH") ||
                        KEY_HEX.findAll(content).any { it.value.equals("014a", ignoreCase = true) }
                    ) {
                        hasBtnTouch = true
                    }
                    if (content.contains("BTN_TOOL_PEN") ||
                        KEY_HEX.findAll(content).any { it.value.equals("0140", ignoreCase = true) }
                    ) {
                        stylus = true
                    }
                }

                "SYN" -> if (content.contains("SYN_MT_REPORT")) hasMtReport = true
            }
        }

        fun build(): TouchInputDevice {
            val x = mtX ?: singleX ?: IntRange(0, 0)
            val y = mtY ?: singleY ?: IntRange(0, 0)
            return TouchInputDevice(
                path = path,
                maxX = x.last,
                maxY = y.last,
                minX = x.first,
                minY = y.first,
                name = name,
                hasSlot = hasSlot,
                hasTrackingId = hasTrackingId,
                hasBtnTouch = hasBtnTouch,
                hasMtReport = hasMtReport,
                isDirect = direct,
                hasStylus = stylus,
                hasTouchMajor = touchMajor != null,
                hasPressure = mtPressure != null || singlePressure != null,
                pressureCode = if (mtPressure != null) ABS_MT_PRESSURE else ABS_PRESSURE,
                pressureMax = (mtPressure ?: singlePressure)?.last ?: 0,
            )
        }
    }
}
