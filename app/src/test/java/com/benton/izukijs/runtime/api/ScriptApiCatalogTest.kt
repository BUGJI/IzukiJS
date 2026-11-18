package com.benton.izukijs.runtime.api

import com.benton.izukijs.TestProjectFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptApiCatalogTest {

    @Test
    fun namespaceNamesAreUniqueAndNonBlank() {
        val names = ScriptApiCatalog.namespaces.map { it.name }
        assertEquals(names.size, names.distinct().size)
        assertTrue(names.all { it.isNotBlank() })
    }

    @Test
    fun needKeysAreUniqueLowercaseAndKnown() {
        val needs = ScriptApiCatalog.namespaces.mapNotNull { it.need }
        assertEquals(needs.size, needs.distinct().size)
        assertTrue(needs.all { it == it.lowercase() && it.isNotBlank() })
        assertEquals(needs.toSet(), ScriptApiCatalog.needKeys)
    }

    @Test
    fun everyEntryHasSuggestions() {
        for (entry in ScriptApiCatalog.all) {
            assertFalse("${entry.name} 缺少补全", entry.suggestions.isEmpty())
            assertEquals(
                "${entry.name} 补全重复",
                entry.suggestions.size,
                entry.suggestions.distinct().size,
            )
        }
    }

    @Test
    fun needKeysMatchScriptNeedsKnown() {
        assertEquals(ScriptApiCatalog.needKeys, com.benton.izukijs.runtime.ScriptNeeds.KNOWN)
    }

    @Test
    fun registeredNamespacesAreDeclaredInCatalog() {
        val registered = registeredNamespaces()
        val declared = ScriptApiCatalog.namespaces.map { it.name }.toSet()

        assertTrue("没有解析到任何命名空间注册", registered.isNotEmpty())
        assertEquals("注册了目录里不存在的命名空间", emptySet<String>(), registered - declared)
        assertEquals("目录里的命名空间没有被注册", emptySet<String>(), declared - registered)
    }

    @Test
    fun suggestionsReferToExistingMethods() {
        val kotlinMethods = HashSet<String>()
        TestProjectFiles.resolveDirectory("src/main/java")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                FUN_DEFINITION.findAll(file.readText(Charsets.UTF_8))
                    .forEach { kotlinMethods += it.groupValues[1] }
            }
        val prelude = TestProjectFiles.read("src/main/assets/runtime/prelude.js")
        PRELUDE_METHOD.findAll(prelude).forEach { kotlinMethods += it.groupValues[1] }
        PRELUDE_FUNCTION.findAll(prelude).forEach { kotlinMethods += it.groupValues[1] }

        val missing = ScriptApiCatalog.all.flatMap { entry ->
            entry.suggestions
                .mapNotNull { SUGGESTION.find(it)?.groupValues?.get(1) }
                .filterNot { it in kotlinMethods }
                .map { "${entry.name}.$it" }
        }

        assertTrue("目录补全指向不存在的方法: $missing", missing.isEmpty())
    }

    /**
     * 从 `ScriptApiEnvironment` 提取 `registerNamespace(...)` 的最后一个字符串参数。
     *
     * 只做源码扫描，不启动 Android；新增 / 删除命名空间时测试会先失败，而不是等运行时
     * 的 require 才暴露。
     */
    private fun registeredNamespaces(): Set<String> {
        val source = TestProjectFiles.read(
            "src/main/java/com/benton/izukijs/runtime/api/ScriptApiEnvironment.kt",
        )
        val marker = "registerNamespace("
        val names = mutableSetOf<String>()
        var start = source.indexOf(marker)
        while (start >= 0) {
            var depth = 0
            var index = start + marker.length - 1
            var end = -1
            while (index < source.length) {
                when (source[index]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            end = index
                            break
                        }
                    }
                }
                index++
            }
            if (end < 0) break
            val call = source.substring(start, end + 1)
            // 允许空字符串参数（例如 captureScreen("")），只取最后一个非空字面量。
            Regex("\"([^\"]*)\"").findAll(call)
                .lastOrNull { it.groupValues[1].isNotBlank() }
                ?.let { names += it.groupValues[1] }
            start = source.indexOf(marker, end + 1)
        }
        return names
    }

    private companion object {
        val FUN_DEFINITION = Regex("""fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        val PRELUDE_METHOD = Regex("""\.([A-Za-z_][A-Za-z0-9_]*)\s*=\s*function""")
        val PRELUDE_FUNCTION = Regex("""function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        val SUGGESTION = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\($""")
    }
}
