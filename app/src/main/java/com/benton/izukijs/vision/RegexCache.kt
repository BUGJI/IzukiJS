package com.benton.izukijs.vision

import com.benton.izukijs.core.log.LogBus

/**
 * 有界正则缓存：Pipeline 轮询识别时避免每帧重新编译同一批 pattern。
 *
 * 编译失败也会缓存为 null，并在首次失败时告警，避免刷屏。
 */
internal class RegexCache(
    private val logBus: LogBus,
    private val label: String,
    private val maxEntries: Int = 128,
) {

    private val cache = object : LinkedHashMap<String, Regex?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex?>): Boolean =
            size > maxEntries
    }

    fun get(pattern: String): Regex? = synchronized(cache) {
        if (cache.containsKey(pattern)) return cache[pattern]
        val compiled = runCatching { Regex(pattern) }.getOrElse {
            logBus.warn("$label 正则无效: $pattern")
            null
        }
        cache[pattern] = compiled
        compiled
    }
}
