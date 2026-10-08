package com.benton.izukijs.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [BatchRunner] 的顺序执行、失败策略与截断行为测试。 */
class BatchRunnerTest {

    private fun actions(vararg tools: String): List<BatchRunner.Action<String>> =
        tools.map { BatchRunner.Action(it, "arg-$it") }

    private fun executesAll(): (String, String) -> String = { tool, _ -> """{"ok":true,"tool":"$tool"}""" }

    private fun failures(vararg failing: String): (String) -> Boolean =
        { result -> failing.any { result.contains("\"tool\":\"$it\"") } }

    @Test
    fun runsAllWhenNoFailure() {
        val outcome = BatchRunner.run(
            actions = actions("a", "b", "c"),
            continueOnError = false,
            execute = executesAll(),
            isFailure = { false },
        )
        assertTrue(outcome.ok)
        assertEquals(3, outcome.steps.size)
        assertFalse(outcome.stoppedEarly)
        assertFalse(outcome.truncated)
        assertEquals(listOf(0, 1, 2), outcome.steps.map { it.index })
    }

    @Test
    fun stopsAtFirstFailureByDefault() {
        val outcome = BatchRunner.run(
            actions = actions("a", "b", "c"),
            continueOnError = false,
            execute = executesAll(),
            isFailure = failures("b"),
        )
        assertFalse(outcome.ok)
        assertTrue(outcome.stoppedEarly)
        assertEquals(listOf("a", "b"), outcome.steps.map { it.tool })
        assertFalse(outcome.steps[1].ok)
    }

    @Test
    fun continuesPastFailuresWhenRequested() {
        val outcome = BatchRunner.run(
            actions = actions("a", "b", "c"),
            continueOnError = true,
            execute = executesAll(),
            isFailure = failures("b"),
        )
        assertFalse(outcome.ok)
        assertFalse(outcome.stoppedEarly)
        assertEquals(listOf("a", "b", "c"), outcome.steps.map { it.tool })
    }

    @Test
    fun truncatesWhenExceedingMaxActions() {
        val outcome = BatchRunner.run(
            actions = actions("a", "b", "c", "d"),
            continueOnError = false,
            maxActions = 2,
            execute = executesAll(),
            isFailure = { false },
        )
        assertTrue(outcome.truncated)
        assertEquals(2, outcome.steps.size)
    }

    @Test
    fun executeExceptionBecomesFailure() {
        var calls = 0
        val outcome = BatchRunner.run(
            actions = actions("a", "b"),
            continueOnError = false,
            execute = { tool, _ ->
                calls++
                if (tool == "b") error("boom") else """{"ok":true}"""
            },
            isFailure = { it.startsWith("[错误]") },
        )
        assertFalse(outcome.ok)
        assertTrue(outcome.stoppedEarly)
        assertEquals(2, calls)
        assertTrue(outcome.steps[1].result.startsWith("[错误]"))
    }

    @Test
    fun truncatesLongResults() {
        val outcome = BatchRunner.run(
            actions = actions("a"),
            continueOnError = false,
            execute = { _, _ -> "x".repeat(BatchRunner.MAX_RESULT_CHARS + 500) },
            isFailure = { false },
        )
        assertTrue(outcome.steps[0].result.length < BatchRunner.MAX_RESULT_CHARS + 100)
    }
}
