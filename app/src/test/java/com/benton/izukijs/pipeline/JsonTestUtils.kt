package com.benton.izukijs.pipeline

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

fun assertJsonEquals(expected: Any?, actual: Any?, path: String = "$") {
    when {
        expected is JSONObject && actual is JSONObject -> {
            val expectedKeys = expected.keys().asSequence().toSet()
            val actualKeys = actual.keys().asSequence().toSet()
            assertEquals("JSON 键不一致 @ $path", expectedKeys, actualKeys)
            for (key in expectedKeys) {
                assertJsonEquals(expected.opt(key), actual.opt(key), "$path.$key")
            }
        }

        expected is JSONArray && actual is JSONArray -> {
            assertEquals("JSON 数组长度不一致 @ $path", expected.length(), actual.length())
            for (index in 0 until expected.length()) {
                assertJsonEquals(expected.opt(index), actual.opt(index), "$path[$index]")
            }
        }

        expected is Number && actual is Number -> {
            assertEquals("JSON 数字不一致 @ $path", expected.toDouble(), actual.toDouble(), 1e-9)
        }

        else -> assertEquals("JSON 值不一致 @ $path", expected, actual)
    }
}

fun assertHasError(spec: PipelineSpec, messagePart: String) {
    val issues = PipelineValidator.validate(spec)
    assertTrue(
        "期望包含错误: $messagePart，实际: $issues",
        issues.any { it.severity == PipelineIssueSeverity.Error && it.message.contains(messagePart) },
    )
}

fun assertNoError(spec: PipelineSpec) {
    val errors = PipelineValidator.validate(spec).filter { it.severity == PipelineIssueSeverity.Error }
    if (errors.isNotEmpty()) fail("不应有错误，实际: $errors")
}
