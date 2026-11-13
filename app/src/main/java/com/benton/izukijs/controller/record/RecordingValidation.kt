package com.benton.izukijs.controller.record

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RecordingValidation(
    val ok: Boolean,
    val issues: List<String>,
    val eventCount: Int,
    val frameCount: Int,
    val durationMs: Long,
    val version: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ok", ok)
        .put("eventCount", eventCount)
        .put("frameCount", frameCount)
        .put("durationMs", durationMs)
        .put("version", version)
        .put("issues", JSONArray().apply { issues.forEach { put(it) } })
}

/**
 * 录制结构校验：只读文件，不执行回放。
 *
 * 用于 `record.validate(id)` 与 MCP `recording validate`，在把录制交给
 * `pipeline.replay` 之前快速发现序号、时间线、截图缺失等结构问题。
 */
object RecordingValidator {

    fun validate(reader: RecordingReader): RecordingValidation {
        val issues = ArrayList<String>()
        val events = reader.events
        if (events.isEmpty()) {
            issues.add("录制没有事件")
        }

        val seqs = events.map { it.seq }
        if (seqs.any { it > 0L }) {
            val duplicates = seqs.filter { it > 0L }.groupingBy { it }.eachCount().filterValues { it > 1 }
            if (duplicates.isNotEmpty()) {
                issues.add("事件序号重复: ${duplicates.keys.take(5).joinToString()}")
            }
            if (seqs.zipWithNext().any { (previous, next) -> previous > 0L && next in 1 until previous }) {
                issues.add("事件序号不是单调递增")
            }
        }

        if (events.zipWithNext().any { (previous, next) -> next.timestamp < previous.timestamp }) {
            issues.add("事件时间戳倒退")
        }

        val screenshots = events.filterIsInstance<ScreenshotEvent>()
        val missing = screenshots.map { it.frame }.distinct().filter { path ->
            val file = runCatching { File(reader.dir, path).canonicalFile }.getOrNull()
            file == null || !file.isFile
        }
        if (missing.isNotEmpty()) {
            issues.add("缺失 ${missing.size} 个截图帧: ${missing.take(3).joinToString()}")
        }
        if (screenshots.any { it.width <= 0 || it.height <= 0 }) {
            issues.add("截图事件缺少有效尺寸")
        }
        if (reader.meta.truncated) {
            issues.add("录制帧数已达上限，后续画面被截断")
        }
        if (!reader.meta.complete) {
            issues.add("录制没有正常结束（可能被系统杀进程），尾部事件可能缺失")
        }

        val actionSeqs = events.filterNot { it is ContextEvent || it is SessionStartEvent }
            .map { it.seq }
            .filter { it > 0L }
            .toHashSet()
        val orphanContexts = events.filterIsInstance<ContextEvent>()
            .filter { it.refSeq > 0L && it.refSeq !in actionSeqs }
        if (orphanContexts.isNotEmpty()) {
            issues.add("存在 ${orphanContexts.size} 条找不到对应动作的 context 事件")
        }

        return RecordingValidation(
            ok = issues.isEmpty(),
            issues = issues,
            eventCount = events.size,
            frameCount = screenshots.map { it.frame }.distinct().size,
            durationMs = reader.meta.durationMs,
            version = reader.meta.version,
        )
    }
}
