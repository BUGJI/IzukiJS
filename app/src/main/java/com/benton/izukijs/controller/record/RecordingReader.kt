package com.benton.izukijs.controller.record

import android.graphics.Bitmap
import java.io.File
import kotlin.math.max

class RecordingReader(
    val meta: RecordingMeta,
    val dir: File,
    val events: List<RecordEvent>,
) {
    private val frameCache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
            if (size <= FRAME_CACHE_ENTRIES) return false
            runCatching { eldest.value.recycle() }
            return true
        }
    }

    /**
     * 返回调用方持有的副本；缓存本身保留解码结果，避免重复 JPEG 解码。
     *
     * [maxDimension] 大于 0 时按采样解码（用于缩略图），避免详情页把每帧全尺寸
     * 解码后同时留在内存里导致 OOM。
     */
    fun frame(event: ScreenshotEvent, maxDimension: Int = 0): Bitmap? {
        val key = if (maxDimension > 0) "$maxDimension:${event.frame}" else event.frame
        // 复制必须在缓存锁内完成：clearFrameCache / 替换缓存会回收原图，
        // 若把 copy 放到锁外，可能与回收并发并触发 "recycled bitmap"。
        synchronized(frameCache) {
            frameCache[key]
                ?.takeIf { !it.isRecycled }
                ?.let { cached ->
                    return runCatching { cached.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                }
        }

        val root = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        val file = runCatching { File(dir, event.frame).canonicalFile }.getOrNull() ?: return null
        if (!file.path.startsWith(root.path + File.separator)) return null
        val decoded = BitmapFactoryHelper.decode(file, maxDimension) ?: return null
        synchronized(frameCache) {
            frameCache[key]?.let { runCatching { it.recycle() } }
            frameCache[key] = decoded
            return runCatching { decoded.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        }
    }

    fun clearFrameCache() {
        synchronized(frameCache) {
            frameCache.values.forEach { runCatching { it.recycle() } }
            frameCache.clear()
        }
    }

    private companion object {
        const val FRAME_CACHE_ENTRIES = 8
    }
}

/** 根据目标最长边计算 JPEG 采样率（2 的幂），0 表示不采样。 */
internal fun recordingSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    if (maxDimension <= 0 || width <= 0 || height <= 0) return 1
    var sample = 1
    while (max(width, height) / (sample * 2) >= maxDimension) {
        sample *= 2
    }
    return sample
}

internal object BitmapFactoryHelper {
    fun decode(file: File, maxDimension: Int = 0): Bitmap? = runCatching {
        if (maxDimension <= 0) {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        } else {
            val bounds = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            val options = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = recordingSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        }
    }.getOrNull()
}

/**
 * 旧的线性游标：保留给现有回放调用与测试。
 *
 * 严格回放请使用 [RecordingReplay]，它会区分 screencap / 动作 / 上下文事件，
 * 并给出结构化差异。
 */
class RecordingCursor(private val events: List<RecordEvent>) {

    /** 供 [RecordingReplay] 兼容构造使用。 */
    internal val eventList: List<RecordEvent> get() = events

    private var index = 0
    val mismatches = ArrayList<String>()

    fun nextScreenshot(): ScreenshotEvent? {
        while (index < events.size) {
            val event = events[index++]
            if (event is ScreenshotEvent) return event
            val kind = RecordEvent.kindOf(event)
            if (kind != null) {
                mismatches.add("回放期望截图，但先遇到动作 $kind")
            }
        }
        return null
    }

    fun nextAction(kind: RecordActionKind?): RecordEvent? {
        while (index < events.size) {
            val event = events[index++]
            if (event is ScreenshotEvent) {
                mismatches.add("回放期望动作，但先遇到截图")
                continue
            }
            val actual = RecordEvent.kindOf(event) ?: continue
            if (kind == null || actual == kind) return event
            mismatches.add("回放期望 $kind，但遇到 $actual")
        }
        return null
    }

    fun remaining(): Int = (events.size - index).coerceAtLeast(0)
}
