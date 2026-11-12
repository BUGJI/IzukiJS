package com.benton.izukijs.runtime.api

import com.benton.izukijs.TestProjectFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `prelude.js` 调用的 `xxxRaw` 必须真实存在；反过来，未被 prelude 包装的 Raw 方法必须是
 * 已记录在文档中的低级接口，避免新增 Kotlin 方法后忘记包装或说明。
 */
class PreludeBridgeTest {

    private val prelude: String by lazy {
        TestProjectFiles.read("src/main/assets/runtime/prelude.js")
    }

    private val apiSources: String by lazy {
        TestProjectFiles.list("src/main/java/com/benton/izukijs/runtime/api")
            .filter { it.extension == "kt" }
            .joinToString("\n") { it.readText(Charsets.UTF_8) }
    }

    private val docs: String by lazy { TestProjectFiles.read("docs/SCRIPT_API.md") }

    @Test
    fun preludeOnlyCallsExistingRawMethods() {
        val referenced = preludeRawReferences()
        val defined = kotlinRawMethods()
        val missing = referenced - defined
        assertTrue("prelude 调用了不存在的 Raw 方法: $missing", missing.isEmpty())
    }

    @Test
    fun unwrappedRawMethodsAreDocumentedLowLevelApis() {
        val unwrapped = kotlinRawMethods() - preludeRawReferences()
        assertTrue("未包装且未声明的 Raw 方法: $unwrapped", unwrapped.all { it in LOW_LEVEL_ALLOWLIST })
        for (name in unwrapped) {
            assertTrue("低级接口缺少文档: $name", name.removeSuffix("Raw") in docs)
        }
    }

    private fun preludeRawReferences(): Set<String> =
        RAW_REFERENCE.findAll(prelude)
            .map { it.groupValues[1] }
            .filterNot { it.startsWith("__") }
            .map { it + "Raw" }
            .toSet()

    private fun kotlinRawMethods(): Set<String> =
        RAW_DEFINITION.findAll(apiSources)
            .map { it.groupValues[1] }
            .toSet()

    private companion object {
        val RAW_REFERENCE = Regex("""(?<![A-Za-z0-9_])([A-Za-z_][A-Za-z0-9_]*)Raw\b""")
        val RAW_DEFINITION = Regex("""fun\s+([A-Za-z0-9_]+Raw)\s*\(""")

        /** 直接暴露给脚本、但不需要 prelude 包装的便利 Raw 接口。 */
        val LOW_LEVEL_ALLOWLIST = setOf("findImageRaw", "findImageInRaw", "findColorRaw")
    }
}
