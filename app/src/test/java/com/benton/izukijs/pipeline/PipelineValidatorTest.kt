package com.benton.izukijs.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineValidatorTest {

    @Test
    fun acceptsValidPipeline() {
        val spec = PipelineParser.parse(
            """
            {
              "Start": {
                "recognition": "OCR",
                "expected": ["登录|Login"],
                "next": ["Done", "[JumpBack]Popup"]
              },
              "Done": {"next": []},
              "Popup": {
                "recognition": "TemplateMatch",
                "template": "close.png",
                "action": "Click",
                "next": []
              }
            }
            """.trimIndent(),
        )

        assertNoError(spec)
    }

    @Test
    fun reportsMissingNextReference() {
        val spec = PipelineParser.parse("""{"A": {"next": ["Missing"]}}""")
        assertHasError(spec, "next 引用了不存在的节点: Missing")
    }

    @Test
    fun warnsOnDisabledReference() {
        val spec = PipelineParser.parse(
            """{"A": {"next": ["B"]}, "B": {"enabled": false}}""",
        )

        val issues = PipelineValidator.validate(spec)
        assertTrue(issues.any { it.severity == PipelineIssueSeverity.Warning && it.message.contains("已禁用") })
    }

    @Test
    fun warnsOnUnknownAnchor() {
        val spec = PipelineParser.parse("""{"A": {"next": ["[Anchor]X"]}}""")
        val issues = PipelineValidator.validate(spec)
        assertTrue(issues.any { it.severity == PipelineIssueSeverity.Warning && it.message.contains("锚点未定义: X") })
    }

    @Test
    fun reportsInvalidOcrRegex() {
        val spec = PipelineParser.parse("""{"A": {"recognition": "OCR", "expected": ["["]}}""")
        assertHasError(spec, "expected 正则无效")
    }

    @Test
    fun reportsInvalidReplaceRegex() {
        val spec = PipelineParser.parse(
            """{"A": {"recognition": "OCR", "replace": [["[", "x"]]}}""",
        )
        assertHasError(spec, "replace[0] 正则无效")
    }

    @Test
    fun reportsMissingTemplate() {
        val spec = PipelineParser.parse("""{"A": {"recognition": "TemplateMatch"}}""")
        assertHasError(spec, "TemplateMatch 缺少 template")
    }

    @Test
    fun reportsAndBoxIndexAndMissingSubNode() {
        val spec = PipelineParser.parse(
            """
            {
              "A": {
                "recognition": "And",
                "all_of": ["Missing"],
                "box_index": 5
              }
            }
            """.trimIndent(),
        )
        assertHasError(spec, "子识别引用了不存在的节点: Missing")
        assertHasError(spec, "box_index 越界: 5")
    }

    @Test
    fun reportsMissingActionFields() {
        val spec = PipelineParser.parse(
            """
            {
              "Input": {"action": "InputText"},
              "Command": {"action": "Command"},
              "Shell": {"action": "Shell"},
              "App": {"action": "StartApp"},
              "Custom": {"action": "Custom"}
            }
            """.trimIndent(),
        )
        assertHasError(spec, "InputText 缺少 input_text")
        assertHasError(spec, "Command 缺少 exec")
        assertHasError(spec, "Shell 缺少 cmd")
        assertHasError(spec, "StartApp 缺少 package")
        assertHasError(spec, "Custom 动作缺少 custom_action")
    }

    @Test
    fun reportsMissingCustomRecognition() {
        val spec = PipelineParser.parse("""{"A": {"recognition": "Custom"}}""")
        assertHasError(spec, "Custom 识别缺少 custom_recognition")
    }

    @Test
    fun reportsInvalidTargetShape() {
        val spec = PipelineParser.parse("""{"A": {"action": "Click", "target": [1, 2, 3]}}""")
        assertHasError(spec, "target 必须是 2 或 4 个整数")
    }

    @Test
    fun reportsInvalidNumericFields() {
        val spec = PipelineParser.parse("""{"A": {"rate_limit": -1, "repeat": 0}}""")
        assertHasError(spec, "rate_limit 必须 >= 0")
    }
}
