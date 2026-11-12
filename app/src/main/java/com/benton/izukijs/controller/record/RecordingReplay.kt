package com.benton.izukijs.controller.record

import org.json.JSONArray
import org.json.JSONObject

/**
 * 回放严格度。
 *
 * - [TOLERANT]：允许跳过截图 / 上下文 / 不匹配动作，只记录差异；适合动态界面。
 * - [STRICT]：按录制顺序严格消费事件，类型或参数不一致即返回失败；适合回归测试。
 */
enum class ReplayStrictness { TOLERANT, STRICT }

data class ReplayOptions(
    val strictness: ReplayStrictness = ReplayStrictness.TOLERANT,
    val coordinateTolerance: Int = 3,
    val compareText: Boolean = true,
    val compareShellOutput: Boolean = false,
    val maxMismatches: Int = 50,
) {
    val strict: Boolean get() = strictness == ReplayStrictness.STRICT

    companion object {
        fun fromJson(json: JSONObject?): ReplayOptions {
            if (json == null) return ReplayOptions()
            val strict = json.optBoolean("strict", false)
            return ReplayOptions(
                strictness = if (strict) ReplayStrictness.STRICT else ReplayStrictness.TOLERANT,
                coordinateTolerance = json.optInt("coordinateTolerance", 3).coerceAtLeast(0),
                compareText = json.optBoolean("compareText", true),
                compareShellOutput = json.optBoolean("compareShellOutput", false),
                maxMismatches = json.optInt("maxMismatches", 50).coerceIn(1, 500),
            )
        }
    }
}

data class ReplayMismatch(
    val seq: Long,
    val atMs: Long,
    val expected: String,
    val actual: String,
    val message: String,
    val expectedValue: String? = null,
    val actualValue: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("seq", seq)
        .put("atMs", atMs)
        .put("expected", expected)
        .put("actual", actual)
        .put("message", message)
        .apply {
            expectedValue?.let { put("expectedValue", it) }
            actualValue?.let { put("actualValue", it) }
        }
}

data class ReplayReport(
    val mismatches: List<ReplayMismatch>,
    val notes: List<String>,
    val consumed: Int,
    val total: Int,
    val ok: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ok", ok)
        .put("consumed", consumed)
        .put("total", total)
        .put("mismatches", JSONArray().apply { mismatches.forEach { put(it.toJson()) } })
        .put("notes", JSONArray().apply { notes.forEach { put(it) } })
}

/**
 * 录制回放游标（独立实现，不依赖任何外部框架）。
 *
 * 与旧的 [RecordingCursor] 不同，它把 `screencap` 当成一等事件：
 * 识别用的每一帧必须对应录制中的下一次截图，而不是从采样帧里随便取一张。
 */
class RecordingReplay(
    private val events: List<RecordEvent>,
    val options: ReplayOptions = ReplayOptions(),
) {

    private var index = 0
    private val _mismatches = ArrayList<ReplayMismatch>()
    private val _notes = ArrayList<String>()

    val mismatches: List<ReplayMismatch> get() = _mismatches
    val notes: List<String> get() = _notes
    val consumed: Int get() = index
    val total: Int get() = events.size

    fun isStrict(): Boolean = options.strict

    /** 下一次识别用截图；严格模式下遇到动作会直接判失败。 */
    fun nextScreencap(): ScreenshotEvent? {
        while (index < events.size) {
            val event = events[index]
            when (event) {
                is SessionStartEvent, is ContextEvent -> {
                    index++
                }

                is UserActionEvent -> {
                    // 用户录制里的真实触摸不是流水线动作，回放时只作为观察数据。
                    note("跳过用户动作 seq=${event.seq} kind=${event.kind.jsonValue}")
                    index++
                }

                is ScreenshotEvent -> {
                    index++
                    return event
                }

                else -> {
                    val kind = RecordEvent.kindOf(event)
                    mismatch(
                        expected = "screencap",
                        actual = kind?.name ?: event.javaClass.simpleName,
                        message = "回放期望截图，但先遇到动作 $kind",
                        event = event,
                    )
                    index++
                    if (options.strict) return null
                }
            }
        }
        mismatch("screencap", "end", "录制已结束，没有更多截图", null)
        return null
    }

    /** 下一次动作；[matcher] 可选，用于严格模式下校验参数。 */
    fun expectAction(
        kind: RecordActionKind?,
        matcher: ((RecordEvent) -> Boolean)? = null,
    ): RecordEvent? {
        while (index < events.size) {
            val event = events[index]
            when (event) {
                is SessionStartEvent, is ContextEvent -> {
                    index++
                }

                is UserActionEvent -> {
                    note("跳过用户动作 seq=${event.seq} kind=${event.kind.jsonValue}")
                    index++
                }

                is ScreenshotEvent -> {
                    mismatch(
                        expected = kind?.name ?: "action",
                        actual = "screencap",
                        message = "回放期望动作，但先遇到截图",
                        event = event,
                    )
                    index++
                    if (options.strict) return null
                }

                else -> {
                    val actual = RecordEvent.kindOf(event)
                    if (actual == null) {
                        index++
                        continue
                    }
                    if (kind != null && actual != kind) {
                        mismatch(
                            expected = kind.name,
                            actual = actual.name,
                            message = "回放期望 $kind，但遇到 $actual",
                            event = event,
                        )
                        index++
                        if (options.strict) return null
                        continue
                    }
                    if (matcher != null && !matcher(event)) {
                        mismatch(
                            expected = kind?.name ?: actual.name,
                            actual = actual.name,
                            message = "动作参数不一致",
                            event = event,
                        )
                        index++
                        if (options.strict) return null
                        continue
                    }
                    index++
                    return event
                }
            }
        }
        mismatch(
            expected = kind?.name ?: "action",
            actual = "end",
            message = "回放未找到动作 $kind",
            event = null,
        )
        return null
    }

    fun addMismatch(
        expected: String,
        actual: String,
        message: String,
        event: RecordEvent? = null,
        expectedValue: String? = null,
        actualValue: String? = null,
    ) {
        mismatch(expected, actual, message, event, expectedValue, actualValue)
    }

    fun addNote(message: String) {
        note(message)
    }

    fun finish(): ReplayReport {
        val remaining = events.drop(index).filterNot {
            it is SessionStartEvent || it is ContextEvent
        }
        if (remaining.isNotEmpty()) {
            val first = remaining.first()
            mismatch(
                expected = "end",
                actual = RecordEvent.kindOf(first)?.name ?: first.javaClass.simpleName,
                message = "录制还有 ${remaining.size} 条未消费事件",
                event = first,
            )
        }
        return ReplayReport(
            mismatches = _mismatches.toList(),
            notes = _notes.toList(),
            consumed = index,
            total = events.size,
            ok = _mismatches.isEmpty(),
        )
    }

    private fun mismatch(
        expected: String,
        actual: String,
        message: String,
        event: RecordEvent?,
        expectedValue: String? = null,
        actualValue: String? = null,
    ) {
        if (_mismatches.size >= options.maxMismatches) return
        _mismatches.add(
            ReplayMismatch(
                seq = event?.seq ?: -1L,
                atMs = event?.timestamp ?: 0L,
                expected = expected,
                actual = actual,
                message = message,
                expectedValue = expectedValue,
                actualValue = actualValue,
            ),
        )
    }

    private fun note(message: String) {
        if (_notes.size >= MAX_NOTES) return
        _notes.add(message)
    }

    private companion object {
        const val MAX_NOTES = 200
    }
}
