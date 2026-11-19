package com.benton.izukijs.runtime

import com.benton.izukijs.TestProjectFiles
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 侧与 `tools/autojs-compat` 共享同一份信号 corpus。
 *
 * 两套规则实现（Kotlin 运行时提示 / Node 诊断 CLI）容易各自漂移；这里用最小语料
 * 约束双方对同一段代码必须给出相同的识别结果。
 */
class CompatSignalCorpusTest {

    private val cases: JSONArray by lazy {
        JSONArray(TestProjectFiles.read("tools/autojs-compat/corpus/signals.json"))
    }

    @Test
    fun matchesSharedCorpus() {
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val source = case.getString("source")
            val expected = case.getJSONArray("contains").toStringSet()
            val actual = CompatMode.detectLegacySignals(source).map { it.code }.toSet()
            if (expected.isEmpty()) {
                assertEquals("$name 不应识别出信号: $actual", emptySet<String>(), actual)
            } else {
                assertTrue("$name 缺少信号: ${expected - actual}", actual.containsAll(expected))
            }
        }
    }

    private fun JSONArray.toStringSet(): Set<String> =
        (0 until length()).map { getString(it) }.toSet()
}
