package com.benton.izukijs.runtime.api

import com.benton.izukijs.TestProjectFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/** 保证 catalog 中的命名空间与补全在 `docs/SCRIPT_API.md` 中有对应说明。 */
class ScriptApiDocTest {

    private val docs: String by lazy { TestProjectFiles.read("docs/SCRIPT_API.md") }

    @Test
    fun everyNamespaceHasHeading() {
        for (entry in ScriptApiCatalog.namespaces) {
            val heading = Regex("(?m)^##\\s+`${Regex.escape(entry.docHeading)}`")
            assertTrue("文档缺少命名空间标题: ${entry.name}", heading.containsMatchIn(docs))
        }
    }

    @Test
    fun everyNamespaceSuggestionIsDocumented() {
        for (entry in ScriptApiCatalog.namespaces) {
            for (suggestion in entry.suggestions) {
                val member = suggestion.removeSuffix("(")
                val token = "${entry.name}.$member"
                assertTrue("文档缺少接口: $token", containsToken(docs, token))
            }
        }
    }

    @Test
    fun everyGlobalSuggestionIsDocumented() {
        for (entry in ScriptApiCatalog.globals) {
            for (suggestion in entry.suggestions) {
                val name = suggestion.removeSuffix("(")
                assertTrue("文档缺少全局函数: $name", containsToken(docs, name))
            }
        }
    }

    private fun containsToken(text: String, token: String): Boolean =
        Regex("(?<![A-Za-z0-9_$])${Regex.escape(token)}(?![A-Za-z0-9_$])").containsMatchIn(text)
}
